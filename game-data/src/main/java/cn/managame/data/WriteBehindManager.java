package cn.managame.data;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

final class WriteBehindManager {
    private enum State { RUNNING, CLOSING, CLOSED }
    private final PendingBuffer a, b;
    private volatile PendingBuffer activeBuffer;
    private final Map<EntityMeta, EntityMapper> mappers;
    private final List<LogRepository<?>> logs = new ArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final ReentrantReadWriteLock admission = new ReentrantReadWriteLock();
    private final Object pipeline = new Object();
    private volatile Thread pipelineThread;
    private volatile State state = State.RUNNING;
    private final int batchSize, maxAttempts;
    private final long intervalNanos;
    private final DataErrorHandler handler;
    private final RetryPolicy retry;
    private DataSaveException failure;
    private volatile DataSaveException closeFailure;
    WriteBehindManager(Map<EntityMeta,EntityMapper> mappers, Duration interval, int batchSize,
                       int maxAttempts, DataErrorHandler handler, RetryPolicy retry) {
        this.mappers = Collections.unmodifiableMap(new LinkedHashMap<>(mappers));
        a = new PendingBuffer(mappers.keySet()); b = new PendingBuffer(mappers.keySet()); activeBuffer = a;
        this.batchSize = batchSize; this.maxAttempts = maxAttempts;
        this.handler = handler; this.retry = retry; intervalNanos = interval.toNanos();
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
    // Admission is only a shutdown barrier; buffer swapping never acquires this lock.
    void mutate(Runnable action) {
        admission.readLock().lock();
        try { ensureRunning(); action.run(); }
        finally { admission.readLock().unlock(); }
    }
    void record(EntityMeta meta, Object id, DataOperation operation, Object entity) {
        activeBuffer.record(meta, id, operation, entity);
    }
    private void tick() {
        long start = System.nanoTime();
        try { if (state == State.RUNNING) flush(); }
        catch (Throwable e) { System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Flush pipeline failed", e); }
        finally {
            if (state == State.RUNNING) {
                try { scheduler.schedule(this::tick, Math.max(0, intervalNanos - (System.nanoTime() - start)), TimeUnit.NANOSECONDS); }
                catch (RejectedExecutionException ignored) { /* close has stopped scheduling */ }
            }
        }
    }
    void flush() {
        synchronized (pipeline) {
            if (state == State.CLOSED) return;
            pipelineThread = Thread.currentThread();
            try {
                PendingBuffer old = activeBuffer;
                activeBuffer = old == a ? b : a;
                grace();
                flushBuffer(old);
                for (LogRepository<?> log : logs) log.drain(this, batchSize);
            } finally { pipelineThread = null; }
        }
    }
    private static void grace() {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        boolean interrupted = false;
        while (end - System.nanoTime() > 0) {
            try { TimeUnit.NANOSECONDS.sleep(end - System.nanoTime()); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private void flushBuffer(PendingBuffer buffer) {
        for (var entry : buffer.changes.entrySet()) {
            EntityMeta meta = entry.getKey();
            EntityMapper mapper = mappers.get(meta);
            var classified = new EnumMap<DataOperation,List<Object>>(DataOperation.class);
            entry.getValue().forEach((id, change) -> classified.computeIfAbsent(change.operation(), ignored -> new ArrayList<>())
                    .add(change.operation() == DataOperation.DELETE ? id : change.entity()));
            for (DataOperation op : List.of(DataOperation.DELETE, DataOperation.DELETE_INSERT, DataOperation.INSERT, DataOperation.UPDATE)) {
                List<Object> values = classified.getOrDefault(op, List.of());
                for (int i = 0; i < values.size(); i += batchSize) {
                    var batch = values.subList(i, Math.min(values.size(), i + batchSize));
                    execute(meta.entityType(), op, batch, data -> {
                        switch (op) {
                            case DELETE -> mapper.deleteBatch(meta, data);
                            case DELETE_INSERT -> mapper.deleteInsertBatch(meta, data);
                            case INSERT -> mapper.insertBatch(meta, data);
                            case UPDATE -> mapper.updateBatch(meta, data);
                            default -> throw new AssertionError(op);
                        }
                    });
                }
            }
            entry.getValue().clear();
        }
    }
    <T> void execute(Class<?> type, DataOperation operation, List<T> batch, Consumer<List<T>> action) {
        for (int attempt = 1; ; attempt++) {
            try { action.accept(batch); return; }
            catch (Throwable cause) {
                DataFailure context = new DataFailure(operation == DataOperation.LOG_INSERT
                        ? FrameworkErrorCodes.DATA_LOG_SAVE_FAILED : FrameworkErrorCodes.DATA_SAVE_FAILED,
                        type, operation, Collections.unmodifiableList(batch), cause, attempt);
                boolean again = false;
                if (attempt < maxAttempts) {
                    try { again = retry.shouldRetry(context); }
                    catch (Throwable policyFailure) { if (policyFailure != cause) cause.addSuppressed(policyFailure); }
                }
                if (again) continue;
                if (failure == null) failure = new DataSaveException("One or more accepted batches failed; see DataErrorHandler", cause);
                try { handler.onError(context); }
                catch (Throwable callbackFailure) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "DataErrorHandler failed", callbackFailure);
                }
                return;
            }
        }
    }
    void close() {
        if (Thread.currentThread() == pipelineThread)
            throw new DataOperationException("Cannot close GameData from a persistence callback");
        synchronized (this) {
            if (state == State.CLOSED) { if (closeFailure != null) throw closeFailure; return; }
            admission.writeLock().lock();
            try { state = State.CLOSING; } finally { admission.writeLock().unlock(); }
            scheduler.shutdown();
            synchronized (pipeline) {
                pipelineThread = Thread.currentThread();
                try {
                    grace();
                    // Previous tick is finished; the inactive buffer is older than the active one.
                    flushBuffer(activeBuffer == a ? b : a);
                    flushBuffer(activeBuffer);
                    for (LogRepository<?> log : logs) log.drain(this, batchSize);
                    closeFailure = failure;
                } finally { state = State.CLOSED; pipelineThread = null; }
            }
            if (closeFailure != null) throw closeFailure;
        }
    }
}
