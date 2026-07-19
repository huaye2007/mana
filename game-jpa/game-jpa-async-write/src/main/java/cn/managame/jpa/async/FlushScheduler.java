package cn.managame.jpa.async;

import cn.managame.jpa.core.exception.ConfigurationException;
import cn.managame.jpa.core.exception.ConcurrentWriteException;
import cn.managame.jpa.core.exception.DataTooLargeException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.WriteTask;

import java.io.Closeable;
import java.util.ArrayList;
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
 * worker 数始终有界；同一物理表的 SAVE/DELETE 分片在一个 worker 内顺序执行，不同物理表并行。
 * 批次级瞬时错误整批回灌，数据级错误二分定位，避免批量失败直接放大成 N 次单写。
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
    private final ExecutorService workers;
    private final ReentrantLock flushLock = new ReentrantLock();

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
     * {@code maxConcurrency > 0} 时覆盖 {@code threadCount}；两者都未配置时使用 CPU 数量。
     * 线程类型只决定 worker 的 ThreadFactory，不再改变是否有界。
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

        int concurrency = maxConcurrency > 0
                ? maxConcurrency
                : threadCount > 0 ? threadCount : Math.max(2, Runtime.getRuntime().availableProcessors());
        this.workers = createWorkers(threadMode != null ? threadMode : FlushThreadMode.VIRTUAL, concurrency);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "game-jpa-schedule");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::doFlush,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
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

    private void doFlush() {
        if (!flushLock.tryLock()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            List<TableBuffer.Drain> drains = queue.drainReady();
            if (drains.isEmpty()) {
                return;
            }
            int taskCount = drains.stream().mapToInt(TableBuffer.Drain::size).sum();
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
                // 不取消结果未知的数据库调用；TableBuffer.inFlight 会阻止同表下一批并发执行。
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

    private void flushGroup(TableBuffer buffer, WriteTask.Op op, List<WriteTask> tasks) {
        for (int start = 0; start < tasks.size(); start += maxBatchSize) {
            int end = Math.min(start + maxBatchSize, tasks.size());
            flushRange(buffer, op, tasks, start, end);
        }
    }

    private void flushRange(TableBuffer buffer, WriteTask.Op op,
            List<WriteTask> tasks, int from, int to) {
        List<WriteTask> batch = tasks.subList(from, to);
        try {
            buffer.flush(op, batch);
            queue.complete(batch.size());
        } catch (Exception failure) {
            String entity = batch.isEmpty() ? "unknown" : batch.getFirst().entityName();
            metrics.recordError("asyncWrite.batch", entity, failure);

            if (contains(failure, ConfigurationException.class)) {
                failRange(batch, failure, true);
                return;
            }
            if (contains(failure, DataTooLargeException.class) && batch.size() > 1) {
                if (buffer.replaySafe() || buffer.atomicBatch()) {
                    splitAndFlush(buffer, op, tasks, from, to);
                } else {
                    failRange(batch, failure, false);
                }
                return;
            }
            if (contains(failure, RetriableWriteException.class)) {
                if (buffer.replaySafe()
                        || (buffer.atomicBatch() && contains(failure, ConcurrentWriteException.class))
                        || (buffer.atomicBatch() && contains(failure, DataTooLargeException.class))) {
                    retryRange(buffer, batch, failure);
                } else {
                    // append-only 的 commit 结果可能未知，连接/超时异常不能盲目重放。
                    failRange(batch, failure, false);
                }
                return;
            }
            if (batch.size() > 1) {
                if (buffer.replaySafe() || buffer.atomicBatch()) {
                    splitAndFlush(buffer, op, tasks, from, to);
                } else {
                    failRange(batch, failure, false);
                }
                return;
            }
            failRange(batch, failure, false);
        }
    }

    private void splitAndFlush(TableBuffer buffer, WriteTask.Op op,
            List<WriteTask> tasks, int from, int to) {
        int middle = from + (to - from) / 2;
        flushRange(buffer, op, tasks, from, middle);
        flushRange(buffer, op, tasks, middle, to);
    }

    private void retryRange(TableBuffer buffer, List<WriteTask> tasks, Exception failure) {
        List<WriteTask> retry = new ArrayList<>(tasks.size());
        for (WriteTask task : tasks) {
            task.incrementRetry();
            if (task.retryCount() <= maxRetries) {
                retry.add(task);
                metrics.recordCount("asyncWrite.retry", task.entityName(), 1);
            } else {
                log.warn("[{}] Write still failing after {} retries, dropping: op={}, id={}",
                        task.entityName(), maxRetries, task.op(), task.id(), failure);
                notifyPermanentFailure(task, false);
                queue.complete(1);
            }
        }
        queue.requeue(buffer, retry);
    }

    private void failRange(List<WriteTask> tasks, Exception failure, boolean configuration) {
        for (WriteTask task : tasks) {
            if (configuration) {
                log.error("[{}] Configuration error, dropping write: op={}, id={}",
                        task.entityName(), task.op(), task.id(), failure);
            } else {
                log.warn("[{}] Non-retriable write failure, dropping: op={}, id={}",
                        task.entityName(), task.op(), task.id(), failure);
            }
            notifyPermanentFailure(task, configuration);
        }
        queue.complete(tasks.size());
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

    private static <T extends Throwable> boolean contains(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    public void flush() {
        doFlush();
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
