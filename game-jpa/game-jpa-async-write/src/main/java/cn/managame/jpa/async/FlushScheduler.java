package cn.managame.jpa.async;

import cn.managame.jpa.core.exception.ConfigurationException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.WriteTask;

import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 共享异步刷盘调度器。
 *
 * <p>单线程定时器周期性 {@link AsyncWriteQueue#drainAll} 摘取各物理表缓冲对象、按 maxBatchSize 切片，
 * 把每个 {@link FlushUnit} 派发到 worker 执行落库。worker 线程模型默认虚拟线程、可选有界平台池
 * （见 {@link FlushThreadMode}）。整批失败降级为单条写入并按失败类型分流（瞬时错误重试、确定性失败丢弃）。
 */
public class FlushScheduler implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(FlushScheduler.class);
    private static final int DEFAULT_MAX_BATCH_SIZE = 500;
    public static final long DEFAULT_BATCH_TIMEOUT_MILLIS = 30_000L;

    private final AsyncWriteQueue queue;
    private final int maxRetries;
    private final int maxBatchSize;
    private final long batchTimeoutMillis;
    private final MetricsCollector metrics;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;
    /** 限制单轮刷盘并发执行的单元数，null 表示不限（VIRTUAL 模式下可给阻塞落库封顶并发，避免压垮连接池）。 */
    private final Semaphore concurrencyLimiter;

    private volatile Consumer<WriteTask> failureHandler = task ->
            log.error("[{}] Task permanently failed: op={}, id={}", task.entityName(), task.op(), task.id());

    public FlushScheduler(AsyncWriteQueue queue, long intervalMillis, int maxRetries) {
        this(queue, intervalMillis, maxRetries, FlushThreadMode.VIRTUAL, 0);
    }

    public FlushScheduler(AsyncWriteQueue queue, long intervalMillis, int maxRetries,
            FlushThreadMode threadMode, int threadCount) {
        this(queue, intervalMillis, maxRetries, threadMode, threadCount, MetricsCollector.NOOP);
    }

    public FlushScheduler(AsyncWriteQueue queue, long intervalMillis, int maxRetries,
            FlushThreadMode threadMode, int threadCount, MetricsCollector metrics) {
        this(queue, intervalMillis, maxRetries, threadMode, threadCount, metrics, DEFAULT_MAX_BATCH_SIZE);
    }

    public FlushScheduler(AsyncWriteQueue queue, long intervalMillis, int maxRetries,
            FlushThreadMode threadMode, int threadCount, MetricsCollector metrics, int maxBatchSize) {
        this(queue, intervalMillis, maxRetries, threadMode, threadCount, metrics, maxBatchSize,
                DEFAULT_BATCH_TIMEOUT_MILLIS);
    }

    public FlushScheduler(AsyncWriteQueue queue, long intervalMillis, int maxRetries,
            FlushThreadMode threadMode, int threadCount, MetricsCollector metrics, int maxBatchSize,
            long batchTimeoutMillis) {
        this(queue, intervalMillis, maxRetries, threadMode, threadCount, metrics, maxBatchSize,
                batchTimeoutMillis, 0);
    }

    /**
     * @param maxConcurrency 单轮并发执行的刷盘单元上限，{@code <= 0} 表示不限。VIRTUAL 模式下用它给
     *                       阻塞 JDBC/Mongo 落库封顶并发，避免一次刷盘 fan-out 出过多 vthread 压垮连接池。
     */
    public FlushScheduler(AsyncWriteQueue queue, long intervalMillis, int maxRetries,
            FlushThreadMode threadMode, int threadCount, MetricsCollector metrics, int maxBatchSize,
            long batchTimeoutMillis, int maxConcurrency) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (batchTimeoutMillis <= 0) {
            throw new IllegalArgumentException("batchTimeoutMillis must be positive");
        }
        this.queue = Objects.requireNonNull(queue, "queue");
        this.maxRetries = maxRetries;
        this.maxBatchSize = maxBatchSize > 0 ? maxBatchSize : Integer.MAX_VALUE;
        this.batchTimeoutMillis = batchTimeoutMillis;
        this.metrics = metrics != null ? metrics : MetricsCollector.NOOP;
        this.concurrencyLimiter = maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-jpa-schedule");
            t.setDaemon(true);
            return t;
        });
        this.worker = createWorker(threadMode != null ? threadMode : FlushThreadMode.VIRTUAL, threadCount);

        this.scheduler.scheduleWithFixedDelay(this::doFlush, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private static ExecutorService createWorker(FlushThreadMode mode, int threadCount) {
        if (mode == FlushThreadMode.VIRTUAL) {
            ThreadFactory factory = Thread.ofVirtual().name("game-jpa-flush-", 0).factory();
            return Executors.newThreadPerTaskExecutor(factory);
        }
        int nThreads = threadCount > 0 ? threadCount : Math.max(2, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(nThreads, r -> {
            Thread t = new Thread(r, "game-jpa-flush");
            t.setDaemon(true);
            return t;
        });
    }

    public FlushScheduler onFailure(Consumer<WriteTask> handler) {
        this.failureHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    private void doFlush() {
        long flushStart = System.currentTimeMillis();
        try {
            List<FlushUnit> units = queue.drainAll(maxBatchSize);
            if (units.isEmpty()) {
                return;
            }
            int taskCount = 0;
            for (FlushUnit unit : units) {
                taskCount += unit.size();
            }
            runUnits(units, taskCount);
            metrics.recordCount("asyncWrite.flush.tasks", "scheduler", taskCount);
            metrics.recordLatency("asyncWrite.flush.latency", "scheduler",
                    System.currentTimeMillis() - flushStart);
        } catch (Exception e) {
            log.error("Unexpected error in async flush", e);
            metrics.recordError("asyncWrite.flush", "scheduler", e);
        }
    }

    /**
     * 把各刷盘单元派发到 worker 并等待全部完成或超时。沿用 {@link CountDownLatch} 做 fan-out/join：
     * 单元无返回值、单元体已自吞失败，无需引入 CompletableFuture。
     */
    private void runUnits(List<FlushUnit> units, int taskCount) {
        CountDownLatch latch = new CountDownLatch(units.size());
        for (FlushUnit unit : units) {
            worker.execute(() -> {
                boolean acquired = false;
                try {
                    if (concurrencyLimiter != null) {
                        concurrencyLimiter.acquire();
                        acquired = true;
                    }
                    executeUnit(unit);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | Error e) {
                    log.error("Unexpected error executing async flush unit", e);
                } finally {
                    if (acquired) {
                        concurrencyLimiter.release();
                    }
                    latch.countDown();
                }
            });
        }
        try {
            if (!latch.await(batchTimeoutMillis, TimeUnit.MILLISECONDS)) {
                log.warn("Async flush timed out after {} ms while waiting for {} task(s)",
                        batchTimeoutMillis, taskCount);
                metrics.recordError("asyncWrite.flush.timeout", "scheduler",
                        new TimeoutException("Async flush timed out after " + batchTimeoutMillis + " ms"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for async flush units", e);
        }
    }

    private void executeUnit(FlushUnit unit) {
        List<WriteTask> tasks = unit.tasks();
        try {
            unit.flush(tasks);
            // 成功：任务已在 drain 时脱离缓冲，无需确认。
        } catch (Exception batchEx) {
            String entity = tasks.isEmpty() ? "unknown" : tasks.get(0).entityName();
            log.warn("[{}] Batch flush failed ({} tasks), falling back to single writes",
                    entity, tasks.size(), batchEx);
            metrics.recordError("asyncWrite.batch", entity, batchEx);
            for (WriteTask task : tasks) {
                try {
                    unit.flush(List.of(task));
                } catch (Exception singleEx) {
                    handleSingleFailure(unit.buffer(), task, singleEx);
                }
            }
        }
    }

    /**
     * 单条写失败分流：仅瞬时错误（网络 / 死锁 / 锁超时，{@link RetriableWriteException}）才重试到
     * maxRetries；约束冲突 / 字段超长 / 类型错误等确定性失败重试也不会成功，直接通知失败处理器并丢弃。
     */
    private void handleSingleFailure(TableBuffer buffer, WriteTask task, Exception ex) {
        String entity = task.entityName();
        if (isConfiguration(ex)) {
            // 配置错误（如数据源/物理表未注册）：单独 ERROR + 计量，不与数据错误混在 warn 静默丢弃。
            log.error("[{}] 配置错误，写入被丢弃，请检查数据源/路由/物理表配置: op={}, id={}",
                    entity, task.op(), task.id(), ex);
            notifyFailureHandler(task);
            metrics.recordCount("asyncWrite.misconfiguration", entity, 1);
            return;
        }
        if (isRetriable(ex)) {
            task.incrementRetry();
            if (task.retryCount() < maxRetries) {
                metrics.recordCount("asyncWrite.retry", entity, 1);
                queue.reSubmit(buffer, task);
                return;
            }
            log.warn("[{}] Retriable task still failing after {} retries, dropping: op={}, id={}",
                    entity, maxRetries, task.op(), task.id(), ex);
        } else {
            log.warn("[{}] Non-retriable write failure, dropping: op={}, id={}",
                    entity, task.op(), task.id(), ex);
        }
        notifyFailureHandler(task);
        metrics.recordCount("asyncWrite.permanentFailure", entity, 1);
        // 不 reSubmit = 丢弃。
    }

    private static boolean isRetriable(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof RetriableWriteException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConfiguration(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConfigurationException) {
                return true;
            }
        }
        return false;
    }

    private void notifyFailureHandler(WriteTask task) {
        try {
            failureHandler.accept(task);
        } catch (Exception handlerEx) {
            log.error("failureHandler threw exception: entity={}, op={}, id={}",
                    task.entityName(), task.op(), task.id(), handlerEx);
            metrics.recordError("asyncWrite.failureHandler", task.entityName(), handlerEx);
        }
    }

    public void flush() {
        doFlush();
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("FlushScheduler timer did not terminate in time, forcing final flush");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for FlushScheduler timer to terminate");
        }

        flushPendingOnClose();

        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void flushPendingOnClose() {
        int attempts = Math.max(1, maxRetries);
        for (int i = 0; i < attempts && !queue.isEmpty(); i++) {
            doFlush();
        }
        if (!queue.isEmpty()) {
            log.warn("Async write queue still has {} task(s) after final flush attempts", queue.size());
        }
    }
}
