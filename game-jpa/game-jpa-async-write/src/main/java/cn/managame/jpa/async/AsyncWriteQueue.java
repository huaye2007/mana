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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存异步写缓冲。
 *
 * <p>提交期即按 {@link WriteChannel} 的 {@link cn.managame.jpa.core.write.WriteRouter} 解析出物理目标
 * {@code (dataSource, physicalTable)}，把写任务落入对应的 {@link TableBuffer}（“路由到同一张数据表的
 * 缓存对象”）：实体缓存按 id 合并到最终态（{@link MergeBuffer}），日志只追加（{@link AppendBuffer}）。
 * 刷盘期由 {@link FlushScheduler} 调 {@link #drainAll} 按物理表 + maxBatchSize 切片后批量落库。
 *
 * <p>进程崩溃会丢失尚未刷盘的任务——这是既定取舍，不做 WAL/本地持久化。
 */
public class AsyncWriteQueue implements WriteTaskSubmitter, WriteChannelRegistry, Closeable {

    /** 缓冲对象连续空闲多少轮后回收，避免按时间/范围分表时空桶无限累积。 */
    private static final int IDLE_EVICT_CYCLES = 5;

    private record BufferKey(String entityName, WriteDestination dest) {
    }

    private final ConcurrentHashMap<String, WriteChannel> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BufferKey, TableBuffer> buffers = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean(false);
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

    // ==================== 通道注册 ====================

    @Override
    public void register(WriteChannel channel) {
        Objects.requireNonNull(channel, "channel");
        channels.put(channel.entityName(), channel);
    }

    // ==================== 提交（合并通道） ====================

    @Override
    public void submit(String entityName, WriteTaskSubmitter.Op op, Object entity, Object id) {
        ensureOpen();
        WriteChannel channel = channel(entityName);
        if (!(channel instanceof WriteChannel.Merge merge)) {
            throw new IllegalStateException("submit() requires a merge channel but '"
                    + entityName + "' is an append channel; use append()");
        }
        WriteDestination dest = merge.router().resolve(entity, id, null);
        BufferKey key = new BufferKey(entityName, dest);
        MergeBuffer buffer = (MergeBuffer) buffers.computeIfAbsent(key, k -> new MergeBuffer(dest, merge.flusher()));
        if (isFull(buffer.containsKey(id))) {
            reject(entityName);
        }
        if (buffer.add(new WriteTask(entityName, toInternalOp(op), entity, id))) {
            pending.incrementAndGet();
        }
        rescueIfEvicted(key, buffer, () -> new MergeBuffer(dest, merge.flusher()));
    }

    // ==================== 提交（追加通道） ====================

    @Override
    public void append(String entityName, Object entity) {
        append(entityName, entity, null);
    }

    @Override
    public void append(String entityName, Object entity, Object routingKey) {
        ensureOpen();
        Objects.requireNonNull(entity, "entity");
        WriteChannel channel = channel(entityName);
        if (!(channel instanceof WriteChannel.Append append)) {
            throw new IllegalStateException("append() requires an append channel but '"
                    + entityName + "' is a merge channel; use submit()");
        }
        if (isFull(false)) {
            reject(entityName);
        }
        WriteDestination dest = append.router().resolve(entity, null, routingKey);
        BufferKey key = new BufferKey(entityName, dest);
        AppendBuffer buffer = (AppendBuffer) buffers.computeIfAbsent(key, k -> new AppendBuffer(dest, append.flusher()));
        buffer.add(new WriteTask(entityName, WriteTask.Op.SAVE, entity, null));
        pending.incrementAndGet();
        rescueIfEvicted(key, buffer, () -> new AppendBuffer(dest, append.flusher()));
    }

    // ==================== 刷盘侧（包内可见） ====================

    /** 摘取所有缓冲对象的内容，按物理表 + op + maxBatchSize 切成刷盘单元。 */
    List<FlushUnit> drainAll(int maxBatchSize) {
        int batchSize = maxBatchSize > 0 ? maxBatchSize : Integer.MAX_VALUE;
        List<FlushUnit> all = new ArrayList<>();
        int drained = 0;
        // 仅 drainer 单线程执行，可在遍历中安全删除空闲缓冲对象。
        for (Map.Entry<BufferKey, TableBuffer> entry : buffers.entrySet()) {
            TableBuffer buffer = entry.getValue();
            List<FlushUnit> units = buffer.drainSlices(batchSize);
            if (units.isEmpty()) {
                drained += evictIfIdle(entry.getKey(), buffer, all, batchSize);
                continue;
            }
            buffer.resetIdle();
            for (FlushUnit unit : units) {
                all.add(unit);
                drained += unit.size();
            }
        }
        if (drained > 0) {
            pending.addAndGet(-drained);
        }
        recordQueueGauges();
        return all;
    }

    /**
     * 缓冲对象连续空闲达到阈值则回收（防止按时间/范围分表的空桶无限累积）。摘除后再兜底 drain 一次，
     * 捞回“空判定与摘除之间”可能刚 offer 进来的零星任务；活跃桶不会空闲到阈值，已死的时间桶无生产者，
     * 故残留窗口在实践中可忽略（与不做零丢失的取舍一致）。
     *
     * @return 兜底 drain 出的任务数（计入 pending 回收）
     */
    private int evictIfIdle(BufferKey key, TableBuffer buffer, List<FlushUnit> sink, int batchSize) {
        if (!buffer.markIdleAndShouldEvict(IDLE_EVICT_CYCLES) || !buffers.remove(key, buffer)) {
            return 0;
        }
        int late = 0;
        for (FlushUnit unit : buffer.drainSlices(batchSize)) {
            sink.add(unit);
            late += unit.size();
        }
        return late;
    }

    /**
     * 失败重试回灌：放回任务所属缓冲。重试不受背压/关闭限制——优雅关闭的最终刷盘也要靠它把
     * 失败任务保留到下一轮重试，而不是丢弃。
     */
    void reSubmit(TableBuffer buffer, WriteTask task) {
        if (buffer.reAdd(task)) {
            pending.incrementAndGet();
        }
    }

    // ==================== 状态 ====================

    public boolean isEmpty() {
        return pending.get() <= 0;
    }

    public int size() {
        return Math.max(0, pending.get());
    }

    /** 当前存活的物理表缓冲对象数量（含已空但未到回收阈值的）。仅供测试观察回收行为。 */
    int bufferCount() {
        return buffers.size();
    }

    @Override
    public void close() {
        closeForSubmissions();
        recordQueueGauges();
    }

    /** 停止接受新业务写，但已入缓冲的任务仍可被最终刷盘摘取。 */
    public void closeForSubmissions() {
        closed.set(true);
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ==================== 内部 ====================

    /**
     * 防御提交与缓冲回收的竞态：若刚写入的缓冲对象已被 drainer 回收（不在 map 中），把它残留的任务搬到当前
     * 存活的缓冲对象。任务在写入时已计入 pending，搬移用 {@code reAdd} 不再改计数（由后续 drain 抵消），
     * 故不丢、不重、计数不漂。活跃缓冲需连续多轮空闲才会被回收，正常路径下本方法只是一次命中即返回的 map.get。
     */
    private void rescueIfEvicted(BufferKey key, TableBuffer used, Supplier<TableBuffer> factory) {
        while (buffers.get(key) != used) {
            TableBuffer live = buffers.computeIfAbsent(key, k -> factory.get());
            if (live == used) {
                return;
            }
            for (FlushUnit unit : used.drainSlices(Integer.MAX_VALUE)) {
                for (WriteTask task : unit.tasks()) {
                    live.reAdd(task);
                }
            }
            used = live;
        }
    }

    private WriteChannel channel(String entityName) {
        WriteChannel channel = channels.get(entityName);
        if (channel == null) {
            throw new IllegalStateException("No write channel registered for entity: " + entityName);
        }
        return channel;
    }

    /** 背压判定：到达上限且不是对已存在 key 的合并时拒绝（同 key 合并不增计数，始终放行）。 */
    private boolean isFull(boolean mergesExistingKey) {
        return maxPendingTasks > 0 && !mergesExistingKey && pending.get() >= maxPendingTasks;
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
        metrics.recordGauge("asyncWrite.pending", "queue", Math.max(0, pending.get()));
        if (maxPendingTasks > 0) {
            metrics.recordGauge("asyncWrite.maxPending", "queue", maxPendingTasks);
        }
    }
}
