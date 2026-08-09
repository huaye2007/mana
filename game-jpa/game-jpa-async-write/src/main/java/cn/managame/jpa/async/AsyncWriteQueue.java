package cn.managame.jpa.async;

import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.WriteChannel;
import cn.managame.jpa.core.write.WriteChannelRegistry;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;
import cn.managame.jpa.core.write.WriteTaskSubmitter;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存异步写缓冲。提交时完成物理路由，同一通道、同一物理目标复用一个缓冲对象。
 * <p>
 * 提交路径只做三件事：查通道 → 路由到缓冲 → 放进缓冲的并发 map/队列，没有锁、没有唤醒协议。
 * 非分片实体（绝大多数）恒定落 {@link WriteDestination#DEFAULT}，走缓存好的缓冲引用，
 * 连一次哈希查找都省掉。
 * <p>
 * {@link #size()} 统计排队与在途的全部逻辑任务，因此同时用于背压和优雅关闭判断。
 */
public class AsyncWriteQueue implements WriteTaskSubmitter, WriteChannelRegistry, Closeable {

    private static final class ChannelState {
        final WriteChannel channel;
        final ConcurrentHashMap<WriteDestination, TableBuffer> buffers = new ConcurrentHashMap<>();
        /** 非分片实体恒定命中的缓冲，提交热路径直接用，避免每次哈希查找。 */
        volatile TableBuffer defaultBuffer;

        ChannelState(WriteChannel channel) {
            this.channel = channel;
        }
    }

    private final ConcurrentHashMap<String, ChannelState> channels = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger inFlightBuffers = new AtomicInteger();
    private final Object progressMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxPendingTasks;
    private final MetricsCollector metrics;

    public AsyncWriteQueue() {
        this(0);
    }

    public AsyncWriteQueue(int maxPendingTasks) {
        this(maxPendingTasks, MetricsCollector.NOOP);
    }

    public AsyncWriteQueue(int maxPendingTasks, MetricsCollector metrics) {
        this.maxPendingTasks = maxPendingTasks;
        this.metrics = metrics != null ? metrics : MetricsCollector.NOOP;
        recordQueueGauges();
    }

    @Override
    public void register(WriteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        channels.put(channel.entityName(), new ChannelState(channel));
    }

    @Override
    public void submit(String entityName, WriteTaskSubmitter.Op op, Object entity, Object id) {
        ensureOpen();
        ChannelState state = channel(entityName);
        if (!(state.channel instanceof WriteChannel.Merge merge)) {
            throw new IllegalStateException("submit() requires a merge channel but '"
                    + entityName + "' is an append channel; use append()");
        }
        WriteDestination destination = merge.router().resolve(entity, id, null);
        MergeBuffer buffer = (MergeBuffer) buffer(state, destination);
        if (buffer.add(new WriteTask(entityName, toInternalOp(op), entity, id)) == MergeBuffer.AddResult.FULL) {
            reject(entityName);
        }
    }

    @Override
    public void append(String entityName, Object entity) {
        append(entityName, entity, null);
    }

    @Override
    public void append(String entityName, Object entity, Object routingKey) {
        ensureOpen();
        Objects.requireNonNull(entity, "entity");
        ChannelState state = channel(entityName);
        if (!(state.channel instanceof WriteChannel.Append append)) {
            throw new IllegalStateException("append() requires an append channel but '"
                    + entityName + "' is a merge channel; use submit()");
        }
        if (!tryReserve()) {
            reject(entityName);
        }
        boolean added = false;
        try {
            // 路由可能对分片实体快速失败，失败时必须归还已占用的配额。
            AppendBuffer buffer = (AppendBuffer) buffer(state, append.router().resolve(entity, null, routingKey));
            buffer.add(new WriteTask(entityName, WriteTask.Op.SAVE, entity, null));
            added = true;
        } finally {
            if (!added) {
                releaseReservation();
            }
        }
    }

    /**
     * 摘取所有非空、且当前没有在途批次的物理表缓冲。
     * <p>
     * 直接扫描全部缓冲，不维护 ready queue：缓冲数量的上界是物理表数量，一次扫描只是若干次
     * {@code isEmpty()}，相对一次数据库往返可以忽略，换掉的是一整套 dirty/enqueued 唤醒协议。
     */
    List<TableBuffer.Drain> drainAll() {
        List<TableBuffer.Drain> drains = new ArrayList<>();
        for (ChannelState state : channels.values()) {
            for (TableBuffer buffer : state.buffers.values()) {
                if (!buffer.tryBeginFlush()) {
                    continue;
                }
                inFlightBuffers.incrementAndGet();
                TableBuffer.Drain drain = buffer.drain();
                if (drain.isEmpty()) {
                    finish(buffer);
                } else {
                    drains.add(drain);
                }
            }
        }
        recordQueueGauges();
        return drains;
    }

    /** 释放物理表的刷盘权。与 {@link #drainAll()} 中的 in-flight 自增一一对应。 */
    void finish(TableBuffer buffer) {
        buffer.endFlush();
        if (inFlightBuffers.decrementAndGet() < 0) {
            throw new IllegalStateException("Async write in-flight buffer count became negative");
        }
        synchronized (progressMonitor) {
            progressMonitor.notifyAll();
        }
    }

    /** 成功或永久失败后确认逻辑任务。pending 在这里扣减，而不是 drain 时——在途任务仍算未完成。 */
    void complete(int count) {
        if (count <= 0) {
            return;
        }
        int remaining = pending.addAndGet(-count);
        if (remaining < 0) {
            pending.addAndGet(-remaining);
            throw new IllegalStateException("Async write pending count became negative");
        }
        recordQueueGauges();
    }

    /** 失败任务回灌。合并型缓冲中已存在的新状态优先，被覆盖的旧任务在此确认完成。 */
    void requeue(TableBuffer buffer, List<WriteTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        complete(buffer.requeue(tasks));
    }

    /** worker 未能启动时原样恢复快照，不消耗重试次数。 */
    void restore(TableBuffer.Drain drain) {
        List<WriteTask> all = new ArrayList<>(drain.size());
        all.addAll(drain.saves());
        all.addAll(drain.deletes());
        requeue(drain.buffer(), all);
        finish(drain.buffer());
    }

    boolean tryReserve() {
        while (true) {
            int current = pending.get();
            if (maxPendingTasks > 0 && current >= maxPendingTasks) {
                return false;
            }
            if (pending.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void releaseReservation() {
        complete(1);
    }

    public boolean isEmpty() {
        return pending.get() == 0;
    }

    public int size() {
        return pending.get();
    }

    int bufferCount() {
        int count = 0;
        for (ChannelState state : channels.values()) {
            count += state.buffers.size();
        }
        return count;
    }

    boolean hasInFlight() {
        return inFlightBuffers.get() > 0;
    }

    void awaitInFlight(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0 || !hasInFlight()) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (progressMonitor) {
            while (hasInFlight()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(progressMonitor, remaining);
            }
        }
    }

    @Override
    public void close() {
        closeForSubmissions();
        recordQueueGauges();
    }

    /** 拒绝新的业务提交，但保留队列可刷盘——供优雅关闭时先断流再收尾。 */
    public void closeForSubmissions() {
        closed.set(true);
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 路由到物理表缓冲。非分片实体的 destination 恒为 {@link WriteDestination#DEFAULT} 单例，
     * 用引用比较直接命中缓存字段，跳过哈希查找。
     */
    private TableBuffer buffer(ChannelState state, WriteDestination destination) {
        if (destination == WriteDestination.DEFAULT) {
            TableBuffer cached = state.defaultBuffer;
            if (cached != null) {
                return cached;
            }
        }
        TableBuffer buffer = state.buffers.computeIfAbsent(destination,
                key -> newBuffer(state.channel, key));
        if (destination == WriteDestination.DEFAULT) {
            state.defaultBuffer = buffer;
        }
        return buffer;
    }

    private TableBuffer newBuffer(WriteChannel channel, WriteDestination destination) {
        return switch (channel) {
            case WriteChannel.Merge merge -> new MergeBuffer(destination, merge.flusher(), this);
            case WriteChannel.Append append -> new AppendBuffer(destination, append.flusher());
        };
    }

    private ChannelState channel(String entityName) {
        ChannelState state = channels.get(entityName);
        if (state == null) {
            throw new IllegalStateException("No write channel registered for entity: " + entityName);
        }
        return state;
    }

    private void reject(String entityName) {
        metrics.recordCount("asyncWrite.rejected", entityName, 1);
        throw new RejectedExecutionException("Async write queue is full: maxPendingTasks=" + maxPendingTasks);
    }

    private static WriteTask.Op toInternalOp(WriteTaskSubmitter.Op op) {
        return switch (op) {
            case INSERT, UPDATE -> WriteTask.Op.SAVE;
            case DELETE -> WriteTask.Op.DELETE;
        };
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new RejectedExecutionException("Async write queue is closed");
        }
    }

    private void recordQueueGauges() {
        metrics.recordGauge("asyncWrite.pending", "queue", pending.get());
        if (maxPendingTasks > 0) {
            metrics.recordGauge("asyncWrite.maxPending", "queue", maxPendingTasks);
        }
    }
}
