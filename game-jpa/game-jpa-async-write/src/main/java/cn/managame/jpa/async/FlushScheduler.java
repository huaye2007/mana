package cn.managame.jpa.async;

import cn.managame.jpa.core.exception.ConfigurationException;
import cn.managame.jpa.core.exception.DataTooLargeException;
import cn.managame.jpa.core.exception.PartialBatchException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.WriteTask;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 周期性异步刷盘协调器。
 * <p>
 * 每轮摘取各物理表缓冲的快照，按 {@code maxBatchSize} 切片后派发到有界 worker 池：同一物理表的
 * 分片在一个 worker 内顺序执行，不同物理表并行。
 *
 * <h2>批量写失败的处理</h2>
 * 失败先按语义分成三类（{@link Failure}），再决定整批回灌、整批丢弃还是隔离坏记录：
 * <ul>
 *   <li><b>CONFIG</b>（数据源/物理表未注册）——确定性失败且是配置问题，整批丢弃并单独计量，
 *       不与数据错误混在一起被淹没。</li>
 *   <li><b>TRANSIENT</b>（连接断开、写超时、死锁）——整批同命运，原样回灌下轮重试，
 *       绝不降级成 N 次单条写。</li>
 *   <li><b>DATA</b>（字段超长、唯一键冲突、类型错误）——批里通常只有个别坏记录，需要隔离：
 *       后端报告了坏记录下标就一次分开（好记录整批重放，1 次额外往返），报告不了就二分拆批
 *       （O(log n) 次往返），绝不退化成逐条试。</li>
 * </ul>
 * 隔离到单条后，可重试的（如字段超长，扩容后可能成功）重试到 {@code maxRetries}，其余直接进
 * {@code permanentFailureHandler}。
 * <p>
 * 能否重放取决于通道：合并通道是 SAVE/DELETE 最终态语义，天然幂等；append-only 只有在声明了
 * 原子批次<b>且</b>失败是确定性的（即可推断整批已回滚）时才敢重放——连接中断导致 commit 结果
 * 未知时一律按永久失败处理，宁可丢也不重复写日志。
 */
public class FlushScheduler implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(FlushScheduler.class);

    /** @see FlushOptions#DEFAULT_BATCH_TIMEOUT_MILLIS */
    public static final long DEFAULT_BATCH_TIMEOUT_MILLIS = FlushOptions.DEFAULT_BATCH_TIMEOUT_MILLIS;

    /** 批量写失败的语义分类，决定整批回灌 / 整批丢弃 / 隔离坏记录。 */
    private enum Failure { CONFIG, TRANSIENT, DATA }

    private final AsyncWriteQueue queue;
    private final int maxRetries;
    private final int maxBatchSize;
    private final long batchTimeoutMillis;
    private final MetricsCollector metrics;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private final ReentrantLock flushLock = new ReentrantLock();

    private volatile Consumer<WriteTask> failureHandler = task ->
            log.error("[{}] Task permanently failed: op={}, id={}", task.entityName(), task.op(), task.id());

    /** 默认参数，只指定周期和重试次数。 */
    public FlushScheduler(AsyncWriteQueue queue, long intervalMillis, int maxRetries) {
        this(queue, new FlushOptions().intervalMillis(intervalMillis).maxRetries(maxRetries));
    }

    public FlushScheduler(AsyncWriteQueue queue, FlushOptions options) {
        this(queue, options, MetricsCollector.NOOP);
    }

    public FlushScheduler(AsyncWriteQueue queue, FlushOptions options, MetricsCollector metrics) {
        this.queue = Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(options, "options");
        this.maxRetries = options.maxRetries();
        this.maxBatchSize = options.maxBatchSize();
        this.batchTimeoutMillis = options.batchTimeoutMillis();
        this.metrics = metrics != null ? metrics : MetricsCollector.NOOP;

        this.workers = createWorkers(options.threadMode(), options.threadCount());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "game-jpa-schedule");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::doFlush,
                options.intervalMillis(), options.intervalMillis(), TimeUnit.MILLISECONDS);
    }

    private static ExecutorService createWorkers(FlushThreadMode mode, int concurrency) {
        ThreadFactory factory;
        if (mode == FlushThreadMode.VIRTUAL) {
            factory = Thread.ofVirtual().name("game-jpa-flush-", 0).factory();
        } else {
            factory = runnable -> {
                Thread thread = new Thread(runnable, "game-jpa-flush");
                thread.setDaemon(true);
                return thread;
            };
        }
        return Executors.newFixedThreadPool(concurrency, factory);
    }

    public FlushScheduler onFailure(Consumer<WriteTask> handler) {
        this.failureHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /** 立即执行一轮刷盘。周期调度与手工调用共用一把锁，同一时刻只有一轮在跑。 */
    public void flush() {
        doFlush();
    }

    private void doFlush() {
        if (!flushLock.tryLock()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            List<TableBuffer.Drain> drains = queue.drainAll();
            if (drains.isEmpty()) {
                return;
            }
            int taskCount = 0;
            for (TableBuffer.Drain drain : drains) {
                taskCount += drain.size();
            }
            runDrains(drains, taskCount);
            metrics.recordCount("asyncWrite.flush.tasks", "scheduler", taskCount);
            metrics.recordLatency("asyncWrite.flush.latency", "scheduler",
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("Unexpected error in async flush", e);
            metrics.recordError("asyncWrite.flush", "scheduler", e);
        } finally {
            flushLock.unlock();
        }
    }

    private void runDrains(List<TableBuffer.Drain> drains, int taskCount) {
        List<Future<?>> futures = new ArrayList<>(drains.size());
        for (TableBuffer.Drain drain : drains) {
            try {
                futures.add(workers.submit(() -> executeDrain(drain)));
            } catch (RejectedExecutionException e) {
                queue.restore(drain);
                log.error("Flush worker rejected physical target {}", drain.buffer().destination, e);
            }
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchTimeoutMillis);
        for (Future<?> future : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                recordTimeout(taskCount);
                return;
            }
            try {
                future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // 不取消结果未知的数据库调用；TableBuffer 的刷盘权会挡住同表的下一批。
                recordTimeout(taskCount);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for async flush", e);
            } catch (ExecutionException e) {
                log.error("Unexpected error executing async physical-table drain", e.getCause());
            }
        }
    }

    private void recordTimeout(int taskCount) {
        log.warn("Async flush timed out after {} ms while waiting for {} task(s)",
                batchTimeoutMillis, taskCount);
        metrics.recordError("asyncWrite.flush.timeout", "scheduler",
                new TimeoutException("Async flush timed out after " + batchTimeoutMillis + " ms"));
    }

    private void executeDrain(TableBuffer.Drain drain) {
        try {
            flushGroup(drain.buffer(), WriteTask.Op.SAVE, drain.saves());
            flushGroup(drain.buffer(), WriteTask.Op.DELETE, drain.deletes());
        } finally {
            queue.finish(drain.buffer());
        }
    }

    /** 按 maxBatchSize 切片，保证单次落库调用不穿透数据库的批量上限。 */
    private void flushGroup(TableBuffer buffer, WriteTask.Op op, List<WriteTask> tasks) {
        for (int start = 0; start < tasks.size(); start += maxBatchSize) {
            int end = Math.min(start + maxBatchSize, tasks.size());
            flushBatch(buffer, op, tasks.subList(start, end));
        }
    }

    private void flushBatch(TableBuffer buffer, WriteTask.Op op, List<WriteTask> batch) {
        try {
            buffer.flush(op, batch);
            queue.complete(batch.size());
        } catch (Exception failure) {
            metrics.recordError("asyncWrite.batch", batch.getFirst().entityName(), failure);
            handleFailure(buffer, op, batch, failure);
        }
    }

    private void handleFailure(TableBuffer buffer, WriteTask.Op op, List<WriteTask> batch, Exception failure) {
        Failure kind = classify(failure);
        if (kind == Failure.CONFIG) {
            drop(batch, failure, true);
            return;
        }
        if (!replayable(buffer, kind)) {
            // append-only 且 commit 结果未知：重放会写出重复日志行，宁可交给失败处理器。
            drop(batch, failure, false);
            return;
        }
        if (kind == Failure.TRANSIENT) {
            retry(buffer, batch, failure);
            return;
        }
        if (batch.size() > 1) {
            isolate(buffer, op, batch, failure);
            return;
        }
        // 已隔离到单条：字段超长这类「扩容/迁移后可能成功」的再给几次机会，其余确定性失败直接丢。
        if (contains(failure, RetriableWriteException.class)) {
            retry(buffer, batch, failure);
        } else {
            drop(batch, failure, false);
        }
    }

    /**
     * 能否安全重放整批。合并通道是最终态语义、天然幂等；append-only 只有在整批保证回滚
     * （原子批次 + 确定性失败）时才敢重放，连接中断/超时的结果未知一律不重放。
     */
    private static boolean replayable(TableBuffer buffer, Failure kind) {
        return buffer.idempotent() || (buffer.atomicBatch() && kind == Failure.DATA);
    }

    private static Failure classify(Throwable failure) {
        if (contains(failure, ConfigurationException.class)) {
            return Failure.CONFIG;
        }
        // 字段超长是「数据形状」问题：先隔离到坏记录，隔离后才按可重试处理。
        if (contains(failure, DataTooLargeException.class)) {
            return Failure.DATA;
        }
        if (contains(failure, RetriableWriteException.class)) {
            return Failure.TRANSIENT;
        }
        return Failure.DATA;
    }

    /** 把批内的坏记录分离出来：优先用后端报告的下标，报告不了就二分拆批。 */
    private void isolate(TableBuffer buffer, WriteTask.Op op, List<WriteTask> batch, Exception failure) {
        int[] reported = reportedFailedIndexes(failure, batch.size());
        if (reported == null) {
            int middle = batch.size() / 2;
            flushBatch(buffer, op, batch.subList(0, middle));
            flushBatch(buffer, op, batch.subList(middle, batch.size()));
            return;
        }

        BitSet bad = new BitSet(batch.size());
        for (int index : reported) {
            bad.set(index);
        }
        List<WriteTask> good = new ArrayList<>(batch.size() - reported.length);
        List<WriteTask> rejected = new ArrayList<>(reported.length);
        for (int i = 0; i < batch.size(); i++) {
            (bad.get(i) ? rejected : good).add(batch.get(i));
        }
        metrics.recordCount("asyncWrite.partialBatch", batch.getFirst().entityName(), rejected.size());
        // 好记录一次整批重放；坏记录逐条走单条路径拿到各自的重试/丢弃判定。
        flushBatch(buffer, op, good);
        for (WriteTask task : rejected) {
            flushBatch(buffer, op, List.of(task));
        }
    }

    /**
     * 后端报告的批内失败下标；无法用于隔离时返回 {@code null}，退回二分拆批。
     * <p>
     * 「整批都被标记失败」也算无法定位——MySQL 开启 {@code rewriteBatchedStatements} 后整批会被
     * 改写成一条语句，驱动只能把所有行标记为失败，这时二分才能真正找出坏记录。
     */
    private static int[] reportedFailedIndexes(Throwable failure, int batchSize) {
        PartialBatchException partial = find(failure, PartialBatchException.class);
        if (partial == null) {
            return null;
        }
        int[] indexes = partial.failedIndexes();
        if (indexes.length == 0 || indexes.length >= batchSize) {
            return null;
        }
        for (int index : indexes) {
            if (index < 0 || index >= batchSize) {
                return null;
            }
        }
        return indexes;
    }

    private void retry(TableBuffer buffer, List<WriteTask> batch, Exception failure) {
        List<WriteTask> retrying = new ArrayList<>(batch.size());
        for (WriteTask task : batch) {
            task.incrementRetry();
            if (task.retryCount() <= maxRetries) {
                retrying.add(task);
                metrics.recordCount("asyncWrite.retry", task.entityName(), 1);
            } else {
                log.warn("[{}] Write still failing after {} retries, dropping: op={}, id={}",
                        task.entityName(), maxRetries, task.op(), task.id(), failure);
                notifyPermanentFailure(task, false);
                queue.complete(1);
            }
        }
        queue.requeue(buffer, retrying);
    }

    private void drop(List<WriteTask> batch, Exception failure, boolean configuration) {
        for (WriteTask task : batch) {
            if (configuration) {
                log.error("[{}] Configuration error, dropping write: op={}, id={}",
                        task.entityName(), task.op(), task.id(), failure);
            } else {
                log.warn("[{}] Non-retriable write failure, dropping: op={}, id={}",
                        task.entityName(), task.op(), task.id(), failure);
            }
            notifyPermanentFailure(task, configuration);
        }
        queue.complete(batch.size());
    }

    private void notifyPermanentFailure(WriteTask task, boolean configuration) {
        try {
            failureHandler.accept(task);
        } catch (Exception handlerFailure) {
            log.error("failureHandler threw exception: entity={}, op={}, id={}",
                    task.entityName(), task.op(), task.id(), handlerFailure);
            metrics.recordError("asyncWrite.failureHandler", task.entityName(), handlerFailure);
        }
        metrics.recordCount(configuration
                        ? "asyncWrite.misconfiguration"
                        : "asyncWrite.permanentFailure",
                task.entityName(), 1);
    }

    private static boolean contains(Throwable failure, Class<? extends Throwable> type) {
        return find(failure, type) != null;
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("FlushScheduler timer did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        flushPendingOnClose();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 尽力刷完剩余任务：最多 maxRetries + 1 轮、总计 10 秒，不保证零丢失。 */
    private void flushPendingOnClose() {
        int flushes = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!queue.isEmpty() && flushes < maxRetries + 1 && System.nanoTime() < deadline) {
            if (queue.hasInFlight()) {
                try {
                    queue.awaitInFlight(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            doFlush();
            flushes++;
        }
        if (!queue.isEmpty()) {
            log.warn("Async write queue still has {} queued or in-flight task(s) after final flush attempts",
                    queue.size());
        }
    }
}
