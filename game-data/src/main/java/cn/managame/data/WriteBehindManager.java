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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Write-behind pipeline. Each pass claims the dirty set and saves it table by table — every entity type and
 * every log repository is one lane, the lanes of a pass run in parallel on {@code saveParallelism} threads, and
 * one lane saves its table alone, in operation order and batches (D-WRITE-02). Failures are classified per batch:
 * retryable ones (RetryPolicy) stay pending for the next pass, a non-retryable batch is split into single rows
 * so one bad row cannot drop the others, and only a non-retryable single row is dropped and reported. Two
 * retryable failures in a row on one backend stop every lane of that backend, because storage is then down and
 * every further call would wait out the same timeout; close never saves past its deadline and hands what is left
 * to DataErrorHandler.
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
     * Retryable failures in a row on one backend after which its lanes stop starting saves (D-WRITE-05). One is a
     * row — a lock wait, a deadlock — and stopping on it would let one locked row hold back everything after it;
     * two in a row are storage itself, whose every further call would wait out the same timeout.
     */
    private static final int UNAVAILABLE_AFTER = 2;
    private final PendingBuffer buffer;
    private final Map<EntityMeta, EntityMapper> mappers;
    private final List<LogRepository<?>> logs = new ArrayList<>();
    private final List<Sweeper> sweepers = new ArrayList<>();
    private final long sweepNanos;
    private long lastSweep = System.nanoTime();
    private final ScheduledExecutorService scheduler;
    /** The threads lanes run on; null with a parallelism of one, when every lane runs on the passing thread. */
    private final ExecutorService savers;
    // Shutdown barrier for mutations: high bit = closing, low bits = mutations in progress. One CAS per
    // mutation instead of a read lock; close sets the bit and waits for the count to reach zero.
    private final AtomicLong admission = new AtomicLong();
    /** Held by the thread running a pass, so passes never overlap; lanes of that pass run on savers. */
    private final Object pipeline = new Object();
    /** Threads inside the pipeline — running a lane or reporting drops — which must not wait on it. */
    private final Set<Thread> pipelineThreads = ConcurrentHashMap.newKeySet();
    /** Serializes reports, so DataErrorHandler is never called concurrently however many lanes run. */
    private final Object reports = new Object();
    private volatile State state = State.RUNNING;
    private final int batchSize;
    private final long maxPending;
    private final long intervalNanos, shutdownNanos, pendingWaitNanos;
    private final DataErrorHandler handler;
    private final RetryPolicy retry;
    /** First drop in this GameData's lifetime; close reports it, and again on every later close. */
    private volatile DataSaveException lifetimeFailure;
    /** First drop during the current explicit flush; guarded by {@code reports}. */
    private DataSaveException callFailure;
    /** The latest retryable failure, which close gives as the reason for what it could not save. */
    private volatile Throwable lastRetryable;
    private final LongAdder failedBatches = new LongAdder(), flushes = new LongAdder(), saveNanos = new LongAdder(),
            droppedLogs = new LongAdder(), rejectedChanges = new LongAdder();

    WriteBehindManager(Map<EntityMeta,EntityMapper> mappers, Duration interval, int batchSize, Duration shutdownTimeout,
                       Duration cacheExpire, DataErrorHandler handler, RetryPolicy retry,
                       int maxPendingChanges, Duration pendingChangeWait, int saveParallelism) {
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
        savers = saveParallelism <= 1 ? null
                : Executors.newFixedThreadPool(saveParallelism, Thread.ofPlatform().daemon().name("game-data-save-", 0).factory());
    }

    void addLog(LogRepository<?> log) { logs.add(log); }

    void start() { scheduler.schedule(this::tick, intervalNanos, TimeUnit.NANOSECONDS); }
    void ensureRunning() {
        if (state != State.RUNNING) throw new DataOperationException("GameData is " + state);
    }
    private boolean inPipeline() { return pipelineThreads.contains(Thread.currentThread()); }
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
        if (inPipeline()) {
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

    /** What one pass shares between its lanes: close's deadline and, per backend, retryable failures in a row. */
    private static final class Pass {
        final boolean bounded; final long deadline;
        final Map<Object, AtomicInteger> streaks = new IdentityHashMap<>();   // filled before any lane runs
        final AtomicInteger claimed = new AtomicInteger();
        volatile boolean stopped;
        Pass(boolean bounded, long deadline) { this.bounded = bounded; this.deadline = deadline; }
        boolean pastDeadline() { return bounded && System.nanoTime() - deadline >= 0; }
    }
    /** What a pass left pending, and how many changes it claimed. */
    record Result(int left, int claimed) {}

    /**
     * Saves one table in a pass. A lane is the only thread saving its table, so changes of one key are saved in
     * order; lanes of different tables share nothing but the backend's failure count and the reporting lock.
     */
    final class Lane {
        private final Pass pass;
        private final AtomicInteger streak;
        Lane(Pass pass, Object backend) { this.pass = pass; streak = pass.streaks.get(backend); }

        /**
         * Whether this lane should start no further save: its backend failed retryably {@value #UNAVAILABLE_AFTER}
         * times in a row, or close's deadline for the pass has passed.
         */
        boolean stopping() {
            boolean stop = streak.get() >= UNAVAILABLE_AFTER || pass.pastDeadline();
            if (stop) pass.stopped = true;
            return stop;
        }
        /**
         * Saves one batch. Each item ends in exactly one of: saved, kept (retryable, or not attempted because the
         * lane is {@link #stopping}) or dropped (reported). Returns how many items were kept. Mappers must apply a
         * batch atomically, or recognize their own earlier rows, so a retry cannot duplicate rows (D-ERROR-02).
         */
        <T> int save(Class<?> type, DataOperation operation, List<T> batch, Consumer<List<T>> action,
                     Consumer<T> keep, Function<T, Object> reported) {
            if (stopping()) { batch.forEach(keep); return batch.size(); }
            Throwable cause;
            try { action.accept(batch); streak.set(0); return 0; }
            catch (Throwable error) { cause = error; }
            if (retryable(type, operation, batch, reported, cause, 1)) { batch.forEach(keep); return batch.size(); }
            streak.set(0);   // storage answered, so it is up: what failed is the data
            if (batch.size() == 1) { drop(type, operation, reported.apply(batch.getFirst()), cause, 1); return 0; }
            int kept = 0;
            for (T item : batch) {   // isolate the bad rows; the others still persist
                if (stopping()) { keep.accept(item); kept++; continue; }
                try { action.accept(List.of(item)); streak.set(0); }
                catch (Throwable error) {
                    if (retryable(type, operation, List.of(item), reported, error, 2)) { keep.accept(item); kept++; }
                    else { streak.set(0); drop(type, operation, reported.apply(item), error, 2); }
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
            if (again) { streak.incrementAndGet(); lastRetryable = cause; }
            return again;
        }
        void drop(Class<?> type, DataOperation operation, Object value, Throwable cause, int attempt) {
            WriteBehindManager.this.drop(type, operation, value, cause, attempt);
        }
    }

    int flush() { return flush(false, 0).left(); }
    /**
     * One bounded pass: each table contributes at most what was pending for it when its lane claimed it, so a
     * pass always ends and {@code flushes} advances even while producers keep writing. Returns the number of
     * changes and logs left pending, together with how many changes the pass claimed.
     *
     * <p>The lanes of a pass run in parallel, one per table with something to save, and the pass ends when all
     * of them have. A lane stops starting saves once {@link Lane#stopping} says so — its storage failing, or
     * close's deadline passed — and what it did not attempt stays pending, so a pass during an outage costs about
     * two storage timeouts rather than one per batch (D-WRITE-05).
     */
    private Result flush(boolean bounded, long deadline) {
        synchronized (pipeline) {
            if (state == State.CLOSED) return new Result(0, 0);
            long started = System.nanoTime();
            Pass pass = new Pass(bounded, deadline);
            List<Callable<Integer>> lanes = new ArrayList<>();
            for (EntityMeta meta : buffer.metas()) {
                pass.streaks.computeIfAbsent(mappers.get(meta), ignored -> new AtomicInteger());
                if (!buffer.pending(meta).isEmpty()) lanes.add(lane(() -> flushEntity(meta, new Lane(pass, mappers.get(meta)))));
            }
            for (LogRepository<?> log : logs) {
                pass.streaks.computeIfAbsent(log.storage(), ignored -> new AtomicInteger());
                if (log.queuedLogs() > 0) lanes.add(lane(() -> log.drain(new Lane(pass, log.storage()), batchSize)));
            }
            try {
                int left = run(lanes);
                if (pass.stopped) left = (int) Math.min(Integer.MAX_VALUE, pendingCount());   // skipped ones too
                if (left > 0) LOG.log(System.Logger.Level.WARNING, left + " changes kept pending after retryable save failures");
                return new Result(left, pass.claimed.get());
            } finally { flushes.increment(); saveNanos.add(System.nanoTime() - started); }
        }
    }
    /** Marks the running thread as inside the pipeline for the lane's duration. */
    private Callable<Integer> lane(Callable<Integer> work) {
        return () -> {
            Thread current = Thread.currentThread();
            boolean added = pipelineThreads.add(current);
            try { return work.call(); }
            finally { if (added) pipelineThreads.remove(current); }
        };
    }
    /**
     * Runs a pass's lanes and waits for every one of them — uninterruptibly, because a pass that returned while
     * a lane still saved would let the next pass claim the same table concurrently.
     */
    private int run(List<Callable<Integer>> lanes) {
        int left = 0;
        if (savers == null || lanes.size() <= 1) {
            for (Callable<Integer> lane : lanes) left += call(lane);
            return left;
        }
        List<Future<Integer>> running = new ArrayList<>(lanes.size());
        for (Callable<Integer> lane : lanes) running.add(savers.submit(lane));
        boolean interrupted = false;
        for (Future<Integer> lane : running) {
            for (;;) {
                try { left += lane.get(); break; }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { LOG.log(System.Logger.Level.ERROR, "Save lane failed", e.getCause()); break; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return left;
    }
    private static int call(Callable<Integer> lane) {
        try { return lane.call(); }
        catch (Exception e) { LOG.log(System.Logger.Level.ERROR, "Save lane failed", e); return 0; }
    }
    private long pendingCount() {
        long count = buffer.size();
        for (LogRepository<?> log : logs) count += log.queuedLogs();
        return count;
    }
    private int flushEntity(EntityMeta meta, Lane lane) {
        if (lane.stopping()) return 0;   // its backend already failed in this pass: leave the table unclaimed
        EntityMapper mapper = mappers.get(meta);
        var byOperation = new EnumMap<DataOperation, List<Claimed>>(DataOperation.class);
        int claimed = 0;
        for (Claimed claim : buffer.claim(meta)) {
            claimed++;
            byOperation.computeIfAbsent(claim.change().operation(), ignored -> new ArrayList<>()).add(claim);
        }
        lane.pass.claimed.addAndGet(claimed);
        int left = 0;
        for (DataOperation op : ORDER) {
            List<Claimed> batchable = byOperation.getOrDefault(op, List.of());
            for (int i = 0; i < batchable.size(); i += batchSize) {
                left += lane.save(meta.entityType(), op, batchable.subList(i, Math.min(batchable.size(), i + batchSize)),
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

    /** Reports one drop; serialized, so DataErrorHandler sees one call at a time from whichever lane dropped. */
    void drop(Class<?> type, DataOperation operation, Object value, Throwable cause, int attempt) {
        synchronized (reports) {
            if (callFailure == null) callFailure = new DataSaveException("One or more accepted changes failed; see DataErrorHandler", cause);
            if (lifetimeFailure == null) lifetimeFailure = callFailure;
            failedBatches.increment();
            try { handler.onError(failureOf(type, operation, List.of(value), cause, attempt)); }
            catch (Throwable callbackFailure) { LOG.log(System.Logger.Level.ERROR, "DataErrorHandler failed", callbackFailure); }
        }
    }
    /** A log refused because its queue is full: reported on the inserting thread, not a save failure. */
    void rejectLog(Class<?> type, Object log, int capacity) {
        droppedLogs.increment();
        synchronized (reports) {
            try {
                handler.onError(new DataFailure(FrameworkErrorCodes.DATA_LOG_SAVE_FAILED, type, DataOperation.LOG_INSERT,
                        List.of(log), new DataOperationException("Log queue is full (" + capacity + " waiting)"), 0));
            } catch (Throwable callbackFailure) { LOG.log(System.Logger.Level.ERROR, "DataErrorHandler failed", callbackFailure); }
        }
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
        if (inPipeline()) throw new DataOperationException("Cannot flush from a persistence callback");
        ensureRunning();
        Result result;
        DataSaveException dropped;
        synchronized (pipeline) {
            synchronized (reports) { callFailure = null; }
            long budget = buffer.size();
            do { result = flush(false, 0); budget -= result.claimed(); }
            while (result.left() == 0 && result.claimed() > 0 && budget > 0 && buffer.size() > 0);
            synchronized (reports) { dropped = callFailure; }
        }
        if (dropped != null) throw dropped;
        if (result.left() > 0) throw new DataSaveException(result.left() + " changes remain pending after retryable failures", null);
    }
    /**
     * Stops admission, then saves in passes until nothing is pending or shutdownTimeout elapses; no save starts
     * after that deadline, and a pass that finds storage failing stops and is tried again after a flush
     * interval. close therefore ends within shutdownTimeout plus the storage calls in progress at the deadline.
     * What is still unsaved is then reported through DataErrorHandler with its entities — without another
     * attempt, so the application can dump it before the process is killed — and close throws (D-CLOSE-01).
     */
    void close() {
        if (inPipeline()) throw new DataOperationException("Cannot close GameData from a persistence callback");
        synchronized (this) {
            if (state == State.CLOSED) { if (lifetimeFailure != null) throw lifetimeFailure; return; }
            state = State.CLOSING;
            closeAdmission();
            scheduler.shutdown();
            long deadline = System.nanoTime() + shutdownNanos;
            boolean interrupted = false;
            for (;;) {   // at least once, which also waits for a pass already running
                long before = pendingCount();
                Result result = flush(true, deadline);
                long remaining = pendingCount();
                if (remaining == 0 || System.nanoTime() - deadline >= 0) break;
                // A pass is bounded, so what it did not claim drains at once; only failures wait.
                if (result.left() == 0 && remaining < before) continue;
                try { TimeUnit.NANOSECONDS.sleep(Math.min(intervalNanos, Math.max(0, deadline - System.nanoTime()))); }
                catch (InterruptedException e) { interrupted = true; break; }
            }
            synchronized (pipeline) {
                Thread current = Thread.currentThread();
                pipelineThreads.add(current);
                try {
                    state = State.CLOSED;
                    dropUnsaved();
                } finally { pipelineThreads.remove(current); }
            }
            if (savers != null) savers.shutdown();
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
