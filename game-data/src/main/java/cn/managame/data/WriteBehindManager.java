package cn.managame.data;

import java.util.function.Function;
import cn.managame.core.FrameworkErrorCodes;
import cn.managame.data.PendingBuffer.Change;
import cn.managame.data.PendingBuffer.Claimed;
import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Single-threaded write-behind pipeline. Each flush claims the dirty set, saves it in operation order
 * and batches, and classifies failures: retryable ones (RetryPolicy) stay pending for the next flush,
 * a non-retryable batch is split into single rows so one bad row cannot drop the others, and only a
 * non-retryable single row is dropped and reported. Two retryable failures in a row stop the pass, because
 * storage is then down and every further call would wait out the same timeout; close never saves past its
 * deadline and hands what is left to DataErrorHandler.
 */
final class WriteBehindManager {
    private enum State { RUNNING, CLOSING, CLOSED }
    /** A cache sweep. {@code idleDue} is false on the ticks between idle sweeps, when only a cache over its
     *  entry bound has anything to do. */
    @FunctionalInterface interface Sweeper { void sweep(boolean idleDue); }
    private static final System.Logger LOG = System.getLogger(WriteBehindManager.class.getName());
    /** How often a writer blocked on a full dirty set re-checks; a pass frees room in bursts, not per row. */
    private static final long PENDING_POLL_NANOS = 1_000_000;
    private static final List<DataOperation> ORDER =
            List.of(DataOperation.DELETE, DataOperation.DELETE_INSERT, DataOperation.INSERT, DataOperation.UPDATE);
    /**
     * Retryable failures in a row after which a pass stops starting saves (D-WRITE-05). One is a row — a lock
     * wait, a deadlock — and stopping on it would let one locked row hold back everything after it; two in a
     * row are storage itself, whose every further call would wait out the same timeout.
     */
    private static final int UNAVAILABLE_AFTER = 2;
    private final PendingBuffer buffer;
    private final Map<EntityMeta, EntityMapper> mappers;
    private final List<LogRepository<?>> logs = new ArrayList<>();
    private final List<Sweeper> sweepers = new ArrayList<>();
    private final long sweepNanos;
    private long lastSweep = System.nanoTime();
    private final ScheduledExecutorService scheduler;
    // Shutdown barrier for mutations: high bit = closing, low bits = mutations in progress. One CAS per
    // mutation instead of a read lock; close sets the bit and waits for the count to reach zero.
    private final AtomicLong admission = new AtomicLong();
    private final Object pipeline = new Object();
    private volatile Thread pipelineThread;
    private volatile State state = State.RUNNING;
    private final int batchSize;
    private final long maxPending;
    private final long intervalNanos, shutdownNanos, pendingWaitNanos;
    private final DataErrorHandler handler;
    private final RetryPolicy retry;
    /** First drop in this GameData's lifetime; close reports it, and again on every later close. */
    private volatile DataSaveException lifetimeFailure;
    /** First drop during the current explicit flush; guarded by {@code pipeline}. */
    private DataSaveException callFailure;
    private int claimedCount;      // changes the latest pass claimed; guarded by pipeline
    // The running pass, guarded by pipeline: retryable failures in a row, close's deadline for it (when
    // bounded), and the latest retryable failure, which close gives as the reason for what it could not save.
    private int retryableStreak;
    private boolean passBounded;
    private long passDeadline;
    private Throwable lastRetryable;
    private final LongAdder failedBatches = new LongAdder(), flushes = new LongAdder(), saveNanos = new LongAdder(),
            droppedLogs = new LongAdder(), rejectedChanges = new LongAdder();

    WriteBehindManager(Map<EntityMeta,EntityMapper> mappers, Duration interval, int batchSize, Duration shutdownTimeout,
                       Duration cacheExpire, DataErrorHandler handler, RetryPolicy retry,
                       int maxPendingChanges, Duration pendingChangeWait) {
        this.mappers = Collections.unmodifiableMap(new LinkedHashMap<>(mappers));
        buffer = new PendingBuffer(mappers.keySet());
        this.batchSize = batchSize; this.handler = handler; this.retry = retry;
        maxPending = maxPendingChanges; pendingWaitNanos = pendingChangeWait.toNanos();
        intervalNanos = interval.toNanos(); shutdownNanos = shutdownTimeout.toNanos();
        sweepNanos = Math.max(intervalNanos, cacheExpire.toNanos() / 4);
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
    /** Admission is only the shutdown barrier; flushing never waits for it. */
    void mutate(Runnable action) {
        if (admission.getAndIncrement() < 0) {
            admission.decrementAndGet();
            throw new DataOperationException("GameData is " + state);
        }
        try { ensureRunning(); action.run(); }
        finally { admission.decrementAndGet(); }
    }

    /** Stops admission and waits until every mutation that entered has finished. */
    private void closeAdmission() {
        admission.getAndUpdate(value -> value | Long.MIN_VALUE);
        while ((admission.get() & Long.MAX_VALUE) != 0) LockSupport.parkNanos(10_000);
    }
    /**
     * Admits a change that may add a new key to the dirty set (D-WRITE-04). A key that is already pending
     * only merges and is always admitted; otherwise a full set is waited out for pendingChangeWait and then
     * refused, so an application writing through an outage sees errors instead of growing memory. The wait
     * happens here, before the shutdown barrier is entered, so a waiting writer can never hold up close.
     *
     * @param id the pending key the change would use, or null when the call records several (deleteGroup)
     */
    void admit(EntityMeta meta, Object id) {
        if (buffer.size() < maxPending) return;
        if (id != null && buffer.pending(meta, id)) return;
        // A change recorded from a Mapper or Handler callback would be waiting for the pass it is running
        // inside, so it is refused at once instead of stalling that pass for pendingChangeWait.
        if (Thread.currentThread() == pipelineThread) {
            rejectedChanges.increment();
            throw new DataOperationException("Cannot wait for room in the dirty set from a persistence callback");
        }
        long deadline = System.nanoTime() + pendingWaitNanos;
        for (;;) {
            ensureRunning();                       // closing refuses waiters rather than holding them
            if (System.nanoTime() - deadline >= 0) {
                rejectedChanges.increment();
                throw new DataOperationException(maxPending + " changes are already waiting to be saved");
            }
            LockSupport.parkNanos(Math.min(PENDING_POLL_NANOS, Math.max(0, deadline - System.nanoTime())));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new DataOperationException("Interrupted while waiting for room in the dirty set");
            }
            if (buffer.size() < maxPending) return;
            if (id != null && buffer.pending(meta, id)) return;
        }
    }
    void record(EntityMeta meta, Object id, DataOperation operation, Object entity) {
        buffer.record(meta, id, operation, entity);
    }
    boolean pending(EntityMeta meta, Object id) { return buffer.pending(meta, id); }
    Collection<Change> pending(EntityMeta meta) { return buffer.pending(meta); }
    /** Cache eviction runs here between flushes, never while a claimed change is being saved. */
    void addSweeper(Sweeper sweeper) { sweepers.add(sweeper); }

    private void tick() {
        long start = System.nanoTime();
        try { if (state == State.RUNNING) { flush(); sweep(); } }
        catch (Throwable e) { LOG.log(System.Logger.Level.ERROR, "Flush pipeline failed", e); }
        finally {
            if (state == State.RUNNING) {
                try { scheduler.schedule(this::tick, Math.max(0, intervalNanos - (System.nanoTime() - start)), TimeUnit.NANOSECONDS); }
                catch (RejectedExecutionException ignored) { /* close has stopped scheduling */ }
            }
        }
    }
    /**
     * Idle eviction keeps its own interval; the entry bound is checked every tick, because a cache that is
     * over it should come back down within passes rather than within the idle sweep interval.
     */
    private void sweep() {
        boolean idleDue = System.nanoTime() - lastSweep >= sweepNanos;
        synchronized (pipeline) {
            if (state != State.RUNNING) return;
            if (idleDue) lastSweep = System.nanoTime();
            for (Sweeper sweeper : sweepers) {
                try { sweeper.sweep(idleDue); }
                catch (RuntimeException e) { LOG.log(System.Logger.Level.ERROR, "Cache sweep failed", e); }
            }
        }
    }
    int flush() { return flush(false, 0); }
    /**
     * One bounded pass: each entity type contributes at most what was pending for it when the pass reached
     * it, so a pass always ends and {@code flushes} advances even while producers keep writing. Returns the
     * number of changes and logs left pending; {@link #claimedCount} tells whether the pass had anything to
     * claim at all.
     *
     * <p>The pass also stops starting saves once {@link #stopping} says so — storage failing, or close's
     * deadline passed — and what it did not attempt stays pending, so a pass during an outage costs about two
     * storage timeouts rather than one per batch (D-WRITE-05).
     */
    private int flush(boolean bounded, long deadline) {
        synchronized (pipeline) {
            if (state == State.CLOSED) return 0;
            pipelineThread = Thread.currentThread();
            long started = System.nanoTime();
            int left = 0;
            claimedCount = 0; retryableStreak = 0; passBounded = bounded; passDeadline = deadline;
            try {
                for (EntityMeta meta : buffer.metas()) if (!stopping()) left += flushEntity(meta);
                for (LogRepository<?> log : logs) left += log.drain(this, batchSize);
                if (stopping()) left = (int) Math.min(Integer.MAX_VALUE, pendingCount());   // skipped ones too
                if (left > 0) LOG.log(System.Logger.Level.WARNING, left + " changes kept pending after retryable save failures");
                return left;
            } finally { passBounded = false; flushes.increment(); saveNanos.add(System.nanoTime() - started); pipelineThread = null; }
        }
    }
    /**
     * Whether the running pass should start no further save: storage failed retryably {@value #UNAVAILABLE_AFTER}
     * times in a row, or close's deadline for the pass has passed.
     */
    boolean stopping() {
        return retryableStreak >= UNAVAILABLE_AFTER || (passBounded && System.nanoTime() - passDeadline >= 0);
    }
    private long pendingCount() {
        long count = buffer.size();
        for (LogRepository<?> log : logs) count += log.queuedLogs();
        return count;
    }
    private int flushEntity(EntityMeta meta) {
        EntityMapper mapper = mappers.get(meta);
        var byOperation = new EnumMap<DataOperation, List<Claimed>>(DataOperation.class);
        for (Claimed claim : buffer.claim(meta)) {
            claimedCount++;
            byOperation.computeIfAbsent(claim.change().operation(), ignored -> new ArrayList<>()).add(claim);
        }
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
                        c -> { if (!buffer.requeue(meta, c)) drop(meta.entityType(), op, valueOf(op, c),
                                new DataOperationException("Retryable change conflicts with a newer change"), 1); },
                        c -> valueOf(op, c));
            }
        }
        return left;
    }
    private static Object valueOf(DataOperation op, Claimed c) { return op == DataOperation.DELETE ? c.id() : c.change().entity(); }

    /**
     * Saves one batch. Each item ends in exactly one of: saved, kept (retryable, or not attempted because the
     * pass is {@link #stopping}) or dropped (reported). Returns how many items were kept. Mappers must apply a
     * batch atomically, or recognize their own earlier rows, so a retry cannot duplicate rows (D-ERROR-02).
     */
    <T> int save(Class<?> type, DataOperation operation, List<T> batch, Consumer<List<T>> action,
                 Consumer<T> keep, Function<T, Object> reported) {
        if (stopping()) { batch.forEach(keep); return batch.size(); }
        Throwable cause;
        try { action.accept(batch); retryableStreak = 0; return 0; }
        catch (Throwable error) { cause = error; }
        if (retryable(type, operation, batch, reported, cause, 1)) { batch.forEach(keep); return batch.size(); }
        retryableStreak = 0;   // storage answered, so it is up: what failed is the data
        if (batch.size() == 1) { drop(type, operation, reported.apply(batch.getFirst()), cause, 1); return 0; }
        int kept = 0;
        for (T item : batch) {   // isolate the bad rows; the others still persist
            if (stopping()) { keep.accept(item); kept++; continue; }
            try { action.accept(List.of(item)); retryableStreak = 0; }
            catch (Throwable error) {
                if (retryable(type, operation, List.of(item), reported, error, 2)) { keep.accept(item); kept++; }
                else { retryableStreak = 0; drop(type, operation, reported.apply(item), error, 2); }
            }
        }
        return kept;
    }
    /** Asks the policy; a retryable failure also counts toward {@link #stopping}. */
    private <T> boolean retryable(Class<?> type, DataOperation operation, List<T> batch,
                                  Function<T, Object> reported, Throwable cause, int attempt) {
        boolean again;
        try { again = retry.shouldRetry(failureOf(type, operation, batch.stream().map(reported).toList(), cause, attempt)); }
        catch (Throwable policyFailure) { if (policyFailure != cause) cause.addSuppressed(policyFailure); again = false; }
        if (again) { retryableStreak++; lastRetryable = cause; }
        return again;
    }
    void drop(Class<?> type, DataOperation operation, Object value, Throwable cause, int attempt) {
        if (callFailure == null) callFailure = new DataSaveException("One or more accepted changes failed; see DataErrorHandler", cause);
        if (lifetimeFailure == null) lifetimeFailure = callFailure;
        failedBatches.increment();
        try { handler.onError(failureOf(type, operation, List.of(value), cause, attempt)); }
        catch (Throwable callbackFailure) { LOG.log(System.Logger.Level.ERROR, "DataErrorHandler failed", callbackFailure); }
    }
    /** A log refused because its queue is full: reported on the inserting thread, not a save failure. */
    void rejectLog(Class<?> type, Object log, int capacity) {
        droppedLogs.increment();
        try {
            handler.onError(new DataFailure(FrameworkErrorCodes.DATA_LOG_SAVE_FAILED, type, DataOperation.LOG_INSERT,
                    List.of(log), new DataOperationException("Log queue is full (" + capacity + " waiting)"), 0));
        } catch (Throwable callbackFailure) { LOG.log(System.Logger.Level.ERROR, "DataErrorHandler failed", callbackFailure); }
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
                saveNanos.sum(), singles, groups, droppedLogs.sum(), rejectedChanges.sum());
    }
    /**
     * Persists everything accepted before the call, or throws: DataSaveException when this flush dropped a
     * change (already reported to DataErrorHandler) or left retryable changes pending. Earlier drops do not
     * make later flushes fail; close reports them once more.
     *
     * <p>A pass is bounded, so one may not cover a dirty set that grew while it ran: passes repeat until they
     * have claimed as much as was pending when the call started. Changes recorded after that still wait for
     * the next pass, so quiesce the writers first for a stable barrier.
     */
    void flushNow() {
        if (Thread.currentThread() == pipelineThread) throw new DataOperationException("Cannot flush from a persistence callback");
        ensureRunning();
        int left;
        DataSaveException dropped;
        synchronized (pipeline) {
            callFailure = null;
            long budget = buffer.size();
            do { left = flush(); budget -= claimedCount; }
            while (left == 0 && claimedCount > 0 && budget > 0 && buffer.size() > 0);
            dropped = callFailure;
        }
        if (dropped != null) throw dropped;
        if (left > 0) throw new DataSaveException(left + " changes remain pending after retryable failures", null);
    }
    /**
     * Stops admission, then saves in passes until nothing is pending or shutdownTimeout elapses; no save starts
     * after that deadline, and a pass that finds storage failing stops and is tried again after a flush
     * interval. close therefore ends within shutdownTimeout plus the storage call in progress at the deadline.
     * What is still unsaved is then reported through DataErrorHandler with its entities — without another
     * attempt, so the application can dump it before the process is killed — and close throws (D-CLOSE-01).
     */
    void close() {
        if (Thread.currentThread() == pipelineThread)
            throw new DataOperationException("Cannot close GameData from a persistence callback");
        synchronized (this) {
            if (state == State.CLOSED) { if (lifetimeFailure != null) throw lifetimeFailure; return; }
            state = State.CLOSING;
            closeAdmission();
            scheduler.shutdown();
            long deadline = System.nanoTime() + shutdownNanos;
            boolean interrupted = false;
            for (;;) {   // at least once, which also waits for a pass already running
                long before = pendingCount();
                int left = flush(true, deadline);
                long remaining = pendingCount();
                if (remaining == 0 || System.nanoTime() - deadline >= 0) break;
                // A pass is bounded, so what it did not claim drains at once; only failures wait.
                if (left == 0 && remaining < before) continue;
                try { TimeUnit.NANOSECONDS.sleep(Math.min(intervalNanos, Math.max(0, deadline - System.nanoTime()))); }
                catch (InterruptedException e) { interrupted = true; break; }
            }
            synchronized (pipeline) {
                pipelineThread = Thread.currentThread();
                try {
                    state = State.CLOSED;
                    dropUnsaved();
                } finally { pipelineThread = null; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (lifetimeFailure != null) throw lifetimeFailure;
        }
    }
    /**
     * Reports every change and log still unsaved at close, each as its own failure with attempt 0, and tries
     * none of them again: past the deadline, or with storage failing, another attempt would only spend the time
     * the application needs to dump them.
     */
    private void dropUnsaved() {
        Throwable cause = new DataOperationException("GameData closed before this change was saved", lastRetryable);
        for (EntityMeta meta : buffer.metas())
            for (Claimed c : buffer.claim(meta)) {
                DataOperation op = c.change().operation();
                drop(meta.entityType(), op, valueOf(op, c), cause, 0);
            }
        for (LogRepository<?> log : logs) log.dropQueued(this, cause);
    }
}
