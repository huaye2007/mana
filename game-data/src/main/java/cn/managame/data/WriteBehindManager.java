package cn.managame.data;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.data.PendingBuffer.Change;
import cn.managame.data.PendingBuffer.Claimed;
import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Single-threaded write-behind pipeline. Each flush claims the dirty set, saves it in operation order
 * and batches, and classifies failures: retryable ones (RetryPolicy) stay pending for the next flush,
 * a non-retryable batch is split into single rows so one bad row cannot drop the others, and only a
 * non-retryable single row is dropped and reported.
 */
final class WriteBehindManager {
    private enum State { RUNNING, CLOSING, CLOSED }
    private static final System.Logger LOG = System.getLogger(WriteBehindManager.class.getName());
    private static final List<DataOperation> ORDER =
            List.of(DataOperation.DELETE, DataOperation.DELETE_INSERT, DataOperation.INSERT, DataOperation.UPDATE);
    private final PendingBuffer buffer;
    private final Map<EntityMeta, EntityMapper> mappers;
    private final List<LogRepository<?>> logs = new ArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final ReentrantReadWriteLock admission = new ReentrantReadWriteLock();
    private final Object pipeline = new Object();
    private volatile Thread pipelineThread;
    private volatile State state = State.RUNNING;
    private final int batchSize;
    private final long intervalNanos, shutdownNanos;
    private final DataErrorHandler handler;
    private final RetryPolicy retry;
    private volatile DataSaveException failure;
    private volatile int deferred; // retryable failures left pending by the latest flush
    private final LongAdder failedBatches = new LongAdder(), flushes = new LongAdder(), saveNanos = new LongAdder();
    private volatile DataSaveException closeFailure;

    WriteBehindManager(Map<EntityMeta,EntityMapper> mappers, Duration interval, int batchSize, Duration shutdownTimeout,
                       DataErrorHandler handler, RetryPolicy retry) {
        this.mappers = Collections.unmodifiableMap(new LinkedHashMap<>(mappers));
        buffer = new PendingBuffer(mappers.keySet());
        this.batchSize = batchSize; this.handler = handler; this.retry = retry;
        intervalNanos = interval.toNanos(); shutdownNanos = shutdownTimeout.toNanos();
        var executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("game-data-flush").factory());
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        scheduler = executor;
    }
    void addLog(LogRepository<?> log) { logs.add(log); }
    void start() { scheduler.schedule(this::tick, intervalNanos, TimeUnit.NANOSECONDS); }
    void ensureRunning() {
        if (state != State.RUNNING) throw new DataOperationException("GameData is " + state);
    }
    /** Admission is only the shutdown barrier; flushing never takes this lock. */
    void mutate(Runnable action) {
        admission.readLock().lock();
        try { ensureRunning(); action.run(); }
        finally { admission.readLock().unlock(); }
    }
    void record(EntityMeta meta, Object id, DataOperation operation, Object entity) {
        buffer.record(meta, id, operation, entity);
    }
    /** Newest unsaved change for a cache miss; null when storage is current. */
    Change unsaved(EntityMeta meta, Object id) { return buffer.unsaved(meta, id); }
    PendingBuffer buffer() { return buffer; }

    private void tick() {
        long start = System.nanoTime();
        try { if (state == State.RUNNING) flush(); }
        catch (Throwable e) { LOG.log(System.Logger.Level.ERROR, "Flush pipeline failed", e); }
        finally {
            if (state == State.RUNNING) {
                try { scheduler.schedule(this::tick, Math.max(0, intervalNanos - (System.nanoTime() - start)), TimeUnit.NANOSECONDS); }
                catch (RejectedExecutionException ignored) { /* close has stopped scheduling */ }
            }
        }
    }
    /** One pass over everything pending when it starts; returns the number of retryable leftovers. */
    int flush() {
        synchronized (pipeline) {
            if (state == State.CLOSED) return 0;
            pipelineThread = Thread.currentThread();
            long started = System.nanoTime();
            int left = 0;
            try {
                for (EntityMeta meta : buffer.metas()) left += flushEntity(meta);
                for (LogRepository<?> log : logs) left += log.drain(this, batchSize);
                deferred = left;
                if (left > 0) LOG.log(System.Logger.Level.WARNING, left + " changes kept pending after retryable save failures");
                return left;
            } finally { flushes.increment(); saveNanos.add(System.nanoTime() - started); pipelineThread = null; }
        }
    }
    private int flushEntity(EntityMeta meta) {
        EntityMapper mapper = mappers.get(meta);
        var byOperation = new EnumMap<DataOperation, List<Claimed>>(DataOperation.class);
        for (Claimed claimed : buffer.claim(meta))
            byOperation.computeIfAbsent(claimed.change().operation(), ignored -> new ArrayList<>()).add(claimed);
        int left = 0;
        for (DataOperation op : ORDER) {
            List<Claimed> claimed = byOperation.getOrDefault(op, List.of());
            for (int i = 0; i < claimed.size(); i += batchSize) {
                left += save(meta.entityType(), op, claimed.subList(i, Math.min(claimed.size(), i + batchSize)),
                        batch -> {
                            List<Object> values = new ArrayList<>(batch.size());
                            for (Claimed c : batch) values.add(op == DataOperation.DELETE ? c.id() : c.change().entity());
                            switch (op) {
                                case DELETE -> mapper.deleteBatch(meta, values);
                                case DELETE_INSERT -> mapper.deleteInsertBatch(meta, values);
                                case INSERT -> mapper.insertBatch(meta, values);
                                case UPDATE -> mapper.updateBatch(meta, values);
                                default -> throw new AssertionError(op);
                            }
                        },
                        c -> buffer.completed(meta, c),
                        c -> { if (!buffer.requeue(meta, c)) drop(meta.entityType(), op, valueOf(op, c),
                                new DataOperationException("Retryable change conflicts with a newer change"), 1); },
                        c -> valueOf(op, c));
            }
        }
        return left;
    }
    private static Object valueOf(DataOperation op, Claimed c) { return op == DataOperation.DELETE ? c.id() : c.change().entity(); }

    /**
     * Saves one batch. Each item ends in exactly one of: saved, kept (retryable) or dropped (reported).
     * Returns how many items were kept. Mappers must apply a batch atomically so a retry cannot duplicate rows.
     */
    <T> int save(Class<?> type, DataOperation operation, List<T> batch, Consumer<List<T>> action,
                 Consumer<T> saved, Consumer<T> keep, java.util.function.Function<T, Object> reported) {
        Throwable cause;
        try { action.accept(batch); batch.forEach(saved); return 0; }
        catch (Throwable error) { cause = error; }
        if (retryable(type, operation, batch, reported, cause, 1)) { batch.forEach(keep); return batch.size(); }
        if (batch.size() == 1) { dropAndRelease(type, operation, batch.getFirst(), reported, cause, 1, saved); return 0; }
        int kept = 0;
        for (T item : batch) {   // isolate the bad rows; the others still persist
            try { action.accept(List.of(item)); saved.accept(item); }
            catch (Throwable error) {
                if (retryable(type, operation, List.of(item), reported, error, 2)) { keep.accept(item); kept++; }
                else dropAndRelease(type, operation, item, reported, error, 2, saved);
            }
        }
        return kept;
    }
    private <T> boolean retryable(Class<?> type, DataOperation operation, List<T> batch,
                                  java.util.function.Function<T, Object> reported, Throwable cause, int attempt) {
        if (state == State.CLOSED) return false;
        try { return retry.shouldRetry(failureOf(type, operation, batch.stream().map(reported).toList(), cause, attempt)); }
        catch (Throwable policyFailure) { if (policyFailure != cause) cause.addSuppressed(policyFailure); return false; }
    }
    private <T> void dropAndRelease(Class<?> type, DataOperation operation, T item,
                                    java.util.function.Function<T, Object> reported, Throwable cause, int attempt, Consumer<T> release) {
        drop(type, operation, reported.apply(item), cause, attempt);
        release.accept(item); // no longer unsaved: storage keeps whatever it had
    }
    void drop(Class<?> type, DataOperation operation, Object value, Throwable cause, int attempt) {
        if (failure == null) failure = new DataSaveException("One or more accepted changes failed; see DataErrorHandler", cause);
        failedBatches.increment();
        try { handler.onError(failureOf(type, operation, List.of(value), cause, attempt)); }
        catch (Throwable callbackFailure) { LOG.log(System.Logger.Level.ERROR, "DataErrorHandler failed", callbackFailure); }
    }
    private static DataFailure failureOf(Class<?> type, DataOperation operation, List<?> values, Throwable cause, int attempt) {
        return new DataFailure(operation == DataOperation.LOG_INSERT
                ? FrameworkErrorCodes.DATA_LOG_SAVE_FAILED : FrameworkErrorCodes.DATA_SAVE_FAILED,
                type, operation, Collections.unmodifiableList(values), cause, attempt);
    }
    DataStats stats(long singles, long groups) {
        long queued = 0;
        for (var log : logs) queued += log.queuedLogs();
        return new DataStats(state == State.RUNNING, buffer.size(), queued, failedBatches.sum(), flushes.sum(),
                saveNanos.sum(), singles, groups);
    }
    /** Persists everything accepted before the call, or throws; retryable leftovers stay pending. */
    void flushNow() {
        if (Thread.currentThread() == pipelineThread) throw new DataOperationException("Cannot flush from a persistence callback");
        ensureRunning();
        int left = flush();
        if (failure != null) throw failure;
        if (left > 0) throw new DataSaveException(left + " changes remain pending after retryable failures", null);
    }
    /**
     * Stops admission, then flushes until nothing is pending or shutdownTimeout elapses. Remaining
     * retryable changes are then reported through DataErrorHandler (with their entities, so the
     * application can dump them) and close throws.
     */
    void close() {
        if (Thread.currentThread() == pipelineThread)
            throw new DataOperationException("Cannot close GameData from a persistence callback");
        synchronized (this) {
            if (state == State.CLOSED) { if (closeFailure != null) throw closeFailure; return; }
            admission.writeLock().lock();
            try { state = State.CLOSING; } finally { admission.writeLock().unlock(); }
            scheduler.shutdown();
            long deadline = System.nanoTime() + shutdownNanos;
            boolean interrupted = false;
            while (flush() > 0 && System.nanoTime() - deadline < 0) {
                try { TimeUnit.NANOSECONDS.sleep(Math.min(intervalNanos, Math.max(0, deadline - System.nanoTime()))); }
                catch (InterruptedException e) { interrupted = true; break; }
            }
            synchronized (pipeline) {
                pipelineThread = Thread.currentThread();
                try {
                    state = State.CLOSED;   // retryable failures now drop instead of requeueing
                    flush0();
                    closeFailure = failure;
                } finally { pipelineThread = null; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (closeFailure != null) throw closeFailure;
        }
    }
    /** Final pass after CLOSED: one more attempt, then anything still failing is dropped and reported. */
    private void flush0() {
        for (EntityMeta meta : buffer.metas()) flushEntity(meta);
        for (LogRepository<?> log : logs) log.drain(this, batchSize);
    }
}
