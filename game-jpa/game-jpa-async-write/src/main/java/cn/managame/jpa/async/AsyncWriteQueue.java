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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存异步写缓冲。提交时完成物理路由，同一通道、同一物理目标复用一个缓冲。
 * <p>
 * 活跃缓冲通过 ready queue 驱动刷盘，不周期扫描空缓冲；每个缓冲最多一个在途刷盘任务。
 * {@link #size()} 统计排队和在途的全部逻辑任务，因此同时用于真实背压和优雅关闭判断。
 */
public class AsyncWriteQueue implements WriteTaskSubmitter, WriteChannelRegistry, Closeable {

    private static final class ChannelState {
        final WriteChannel channel;
        final ConcurrentHashMap<WriteDestination, TableBuffer> buffers = new ConcurrentHashMap<>();

        ChannelState(WriteChannel channel) {
            this.channel = channel;
        }
    }

    private final ConcurrentHashMap<String, ChannelState> channels = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TableBuffer> ready = new ConcurrentLinkedQueue<>();
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
        MergeBuffer buffer = (MergeBuffer) state.buffers.computeIfAbsent(destination,
                key -> new MergeBuffer(key, merge.flusher(), this));
        MergeBuffer.AddResult result = buffer.add(new WriteTask(entityName, toInternalOp(op), entity, id));
        if (result == MergeBuffer.AddResult.FULL) {
            reject(entityName);
        }
        signal(buffer);
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
            WriteDestination destination = append.router().resolve(entity, null, routingKey);
            AppendBuffer buffer = (AppendBuffer) state.buffers.computeIfAbsent(destination,
                    key -> new AppendBuffer(key, append.flusher()));
            buffer.add(new WriteTask(entityName, WriteTask.Op.SAVE, entity, null));
            added = true;
            signal(buffer);
        } finally {
            if (!added) {
                releaseReservation();
            }
        }
    }

    /** 仅摘取活跃且没有在途写入的物理表缓冲。 */
    List<TableBuffer.Drain> drainReady() {
        List<TableBuffer.Drain> drains = new ArrayList<>();
        TableBuffer buffer;
        while ((buffer = ready.poll()) != null) {
            if (!buffer.beginDrain()) {
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
        recordQueueGauges();
        return drains;
    }

    /** 完成一个物理表快照；期间产生的新数据会把该缓冲重新放入 ready queue。 */
    void finish(TableBuffer buffer) {
        if (buffer.finishDrain()) {
            ready.offer(buffer);
        }
        int remaining = inFlightBuffers.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("Async write in-flight buffer count became negative");
        }
        synchronized (progressMonitor) {
            progressMonitor.notifyAll();
        }
    }

    /** 成功或永久失败后确认逻辑任务，pending 在这里而不是 drain 时扣减。 */
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

    /**
     * 失败任务回灌。合并型缓冲中已经存在的新状态优先，被覆盖的旧在途任务在此确认完成。
     */
    void requeue(TableBuffer buffer, List<WriteTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        int superseded = buffer.requeue(tasks);
        if (superseded > 0) {
            complete(superseded);
        }
        signal(buffer);
    }

    /** worker 尚未启动时原样恢复快照，不消耗重试次数。 */
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
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (progressMonitor) {
            while (hasInFlight()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(progressMonitor, remaining);
            }
        }
    }

    @Override
    public void close() {
        closeForSubmissions();
        recordQueueGauges();
    }

    public void closeForSubmissions() {
        closed.set(true);
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void signal(TableBuffer buffer) {
        if (buffer.markDirty()) {
            ready.offer(buffer);
        }
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
