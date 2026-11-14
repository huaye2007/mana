package cn.managame.data.internal;

import static cn.managame.FrameworkErrorCodes.DATA_LOG_SAVE_FAILED;
import static cn.managame.FrameworkErrorCodes.DATA_SAVE_FAILED;
import static cn.managame.data.DataOperation.DELETE;
import static cn.managame.data.DataOperation.DELETE_INSERT;
import static cn.managame.data.DataOperation.INSERT;
import static cn.managame.data.DataOperation.LOG_INSERT;
import static cn.managame.data.DataOperation.UPDATE;

import cn.managame.data.DataErrorHandler;
import cn.managame.data.DataFailure;
import cn.managame.data.DataOperation;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.LogWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Writes every entity type's changes through its {@link EntityMapper} and every log type's logs through its
 * {@link LogWriter}, in rounds. In a round each change set and log queue with something waiting is written by one of
 * the write threads, so different types are written at once while one type is written by one thread at a time, in the
 * order its changes were made. A type's deletes are written first, so a row can take the unique value of one deleted in
 * the same round, then replacements, inserts and updates, in batches. Rounds never overlap.
 *
 * <p>Changes and logs are written by the same rule ({@link #writeBatch}). A failure the mapper or writer calls
 * retryable leaves the batch for the next round, and a mapper or writer that failed so twice in a row gets nothing more
 * in the round, which would only wait out the same timeout again. Any other failure may be a row's or the database's,
 * and writing the batch one row at a time tells them apart: when some of its rows are written the database works, so
 * those that fail go to the DataErrorHandler and are dropped; when none is, all are kept for the next round.
 *
 * <p>GameData keeps two, one for the entities' change sets and one for the logs' queues, so that the rounds of each
 * never wait for the other's.
 *
 * <p>Not API: it may change in any release.
 */
public final class ChangeWriter {
    private static final System.Logger LOG = System.getLogger("cn.managame.data");
    private static final List<DataOperation> WRITE_ORDER = List.of(DELETE, DELETE_INSERT, INSERT, UPDATE);
    private static final int MAX_FAILURES_IN_A_ROW = 2;
    private final List<ChangeSet> changeSets;
    private final List<LogQueue> logQueues;
    private final int batchSize;
    private final DataErrorHandler errorHandler;
    /** Writes the types of a round; null with one write thread, when the thread running the round writes them. */
    private final ExecutorService writePool;
    /** Held while a round runs, so rounds never overlap. */
    private final Object roundLock = new Object();

    /** What one round left: changes kept for a later round, changes dropped, and each target's failures in a row. */
    public static final class Round {
        private final boolean hasDeadline;
        private final long deadline;
        /** By mapper or writer, each standing for the database or service it writes to. */
        private final ConcurrentHashMap<Object, AtomicInteger> failuresInARow = new ConcurrentHashMap<>();
        private final AtomicInteger written = new AtomicInteger(), unwritten = new AtomicInteger(), dropped = new AtomicInteger();

        private Round(boolean hasDeadline, long deadline) {
            this.hasDeadline = hasDeadline;
            this.deadline = deadline;
        }

        /** Changes and logs kept for a later round after failures. */
        public int unwritten() { return unwritten.get(); }

        /** Changes and logs handed to the DataErrorHandler. */
        public int dropped() { return dropped.get(); }

        private boolean shouldStop(Object target) {
            return hasDeadline && System.nanoTime() - deadline >= 0 || failuresOf(target).get() >= MAX_FAILURES_IN_A_ROW;
        }

        private void resetFailures(Object target) { failuresOf(target).set(0); }

        private void countFailure(Object target) { failuresOf(target).incrementAndGet(); }

        private AtomicInteger failuresOf(Object target) {
            return failuresInARow.computeIfAbsent(target, key -> new AtomicInteger());
        }
    }

    /** Writes a batch of changes or logs through a mapper or writer. */
    private interface BatchWrite<T> {
        void write(List<T> batch) throws Exception;
    }

    /** What became of one batch: what was written, what is kept for a later round, and what is given up, each with its cause. */
    private static final class BatchResult<T> {
        List<T> written = List.of(), kept = List.of(), givenUp = List.of();
        /** Why each of {@code givenUp} failed, in the same order. */
        List<Throwable> causes = List.of();
        /** Writes that failed, the batch or a single change or log each counting once. */
        int failures;
    }

    public ChangeWriter(List<ChangeSet> changeSets, List<LogQueue> logQueues, int batchSize, int writeThreads, DataErrorHandler errorHandler) {
        this.changeSets = List.copyOf(changeSets);
        this.logQueues = List.copyOf(logQueues);
        this.batchSize = batchSize;
        this.errorHandler = errorHandler;
        writePool = writeThreads > 1
                ? Executors.newFixedThreadPool(writeThreads, Thread.ofPlatform().name("game-data-write-", 0).factory()) : null;
    }

    /** Writes everything waiting, and returns when done. */
    public Round writeAll() { return writeAll(false, 0); }

    /** Writes everything waiting, starting no write after {@code deadline}, a {@link System#nanoTime()}. */
    public Round writeAllBefore(long deadline) { return writeAll(true, deadline); }

    public boolean isEmpty() {
        for (ChangeSet changes : changeSets) if (!changes.isEmpty()) return false;
        for (LogQueue logs : logQueues) if (!logs.isEmpty()) return false;
        return true;
    }

    /** Makes every change set and log queue refuse changes from now on. */
    public void refuseChanges() {
        changeSets.forEach(ChangeSet::close);
        logQueues.forEach(LogQueue::close);
    }

    /** Hands everything still waiting to the DataErrorHandler, as not written for {@code cause}; returns how many. */
    public int dropAll(Throwable cause) {
        synchronized (roundLock) {
            int dropped = 0;
            for (ChangeSet changes : changeSets) {
                List<PendingChange> waiting = changes.takeAll();
                changes.dropped.addAndGet(waiting.size());
                for (PendingChange change : waiting) {
                    change.finished = true;
                    reportFailure(null, DATA_SAVE_FAILED, changes.type.entityClass(), change.operation, change.entityOrId(), cause);
                }
                changes.endRound();
                dropped += waiting.size();
            }
            for (LogQueue logs : logQueues) {
                for (Object log; (log = logs.poll()) != null; dropped++) {
                    logs.dropped.incrementAndGet();
                    reportFailure(null, DATA_LOG_SAVE_FAILED, logs.type, LOG_INSERT, log, cause);
                }
            }
            return dropped;
        }
    }

    public void shutdown() {
        if (writePool != null) writePool.shutdown();
    }

    /** Hands a change or log given up on to the DataErrorHandler, counted as dropped in {@code round} when there is one. */
    public void reportFailure(Round round, int errorCode, Class<?> type, DataOperation operation, Object value, Throwable cause) {
        if (round != null) round.dropped.incrementAndGet();
        try {
            errorHandler.onError(new DataFailure(errorCode, type, operation, value, cause));
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.ERROR, "DataErrorHandler failed", e);
        }
    }

    private Round writeAll(boolean hasDeadline, long deadline) {
        synchronized (roundLock) {
            long start = System.nanoTime();
            Round round = new Round(hasDeadline, deadline);
            List<IntSupplier> writes = new ArrayList<>();
            for (ChangeSet changes : changeSets) if (!changes.isEmpty()) writes.add(() -> writeChanges(changes, round));
            for (LogQueue logs : logQueues) if (!logs.isEmpty()) writes.add(() -> writeLogs(logs, round));
            if (writePool == null || writes.size() < 2) {
                for (IntSupplier write : writes) run(write, round);
                return report(round, start);
            }
            List<Future<?>> futures = new ArrayList<>(writes.size());
            for (IntSupplier write : writes) futures.add(writePool.submit(() -> run(write, round)));
            // Waits for every write even when interrupted: a round that returned early would let the next one write
            // a type this one is still writing.
            boolean interrupted = false;
            for (Future<?> future : futures) {
                while (!future.isDone()) {
                    try {
                        future.get();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException e) {
                        // run() has logged it
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return report(round, start);
        }
    }

    /** Logs at DEBUG what a round that had something to do did. */
    private Round report(Round round, long start) {
        int written = round.written.get(), unwritten = round.unwritten(), dropped = round.dropped();
        if (written + unwritten + dropped > 0)
            LOG.log(System.Logger.Level.DEBUG, () -> "Wrote " + written + (changeSets.isEmpty() ? " logs" : " changes") + " in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms; " + unwritten + " kept for a later round, " + dropped + " dropped");
        return round;
    }

    private static void run(IntSupplier write, Round round) {
        try {
            round.unwritten.addAndGet(write.getAsInt());
        } catch (Throwable e) {
            round.dropped.incrementAndGet();
            LOG.log(System.Logger.Level.ERROR, "writing data failed", e);
        }
    }

    /** Writes one entity type's changes; returns how many stay for a later round. */
    private int writeChanges(ChangeSet changes, Round round) {
        EntityMapper mapper = changes.mapper;
        List<PendingChange> taken = changes.takeAll();
        int finished = 0;
        try {
            Map<DataOperation, List<PendingChange>> byOperation = new EnumMap<>(DataOperation.class);
            for (PendingChange change : taken) byOperation.computeIfAbsent(change.operation, key -> new ArrayList<>()).add(change);
            for (DataOperation operation : WRITE_ORDER) {
                List<PendingChange> ofOperation = byOperation.getOrDefault(operation, List.of());
                for (int from = 0; from < ofOperation.size(); from += batchSize) {
                    BatchResult<PendingChange> result = writeBatch(round, mapper, mapper::isRetryable,
                            batch -> mapper.write(changes.type, operation, batch), ofOperation.subList(from, Math.min(ofOperation.size(), from + batchSize)));
                    changes.written.addAndGet(result.written.size());
                    changes.failures.addAndGet(result.failures);
                    finished += result.written.size() + result.givenUp.size();
                    for (PendingChange change : result.written) change.finished = true;
                    for (int i = 0; i < result.givenUp.size(); i++) {
                        PendingChange change = result.givenUp.get(i);
                        change.finished = true;
                        changes.dropped.incrementAndGet();
                        reportFailure(round, DATA_SAVE_FAILED, changes.type.entityClass(), operation, change.entityOrId(), result.causes.get(i));
                    }
                }
            }
        } finally {
            // A change not marked finished stays, one whose write was cut short by an unexpected failure too: writing
            // it again does no harm.
            changes.endRound();
        }
        return taken.size() - finished;
    }

    /**
     * Writes what was queued when it started, so logs queued meanwhile wait for the next round. Logs kept after a
     * failure go back to the end of the queue; returns how many.
     */
    private int writeLogs(LogQueue logs, Round round) {
        LogWriter writer = logs.writer;
        List<Object> unwritten = new ArrayList<>();
        int remaining = logs.size();
        while (remaining > 0 && !round.shouldStop(writer)) {
            List<Object> batch = new ArrayList<>(Math.min(batchSize, remaining));
            for (Object log; batch.size() < batchSize && remaining > 0 && (log = logs.poll()) != null; remaining--) batch.add(log);
            if (batch.isEmpty()) break;
            BatchResult<Object> result = writeBatch(round, writer, writer::isRetryable, taken -> writer.write(logs.type, taken), batch);
            logs.written.addAndGet(result.written.size());
            logs.failures.addAndGet(result.failures);
            unwritten.addAll(result.kept);
            for (int i = 0; i < result.givenUp.size(); i++) {
                logs.dropped.incrementAndGet();
                reportFailure(round, DATA_LOG_SAVE_FAILED, logs.type, LOG_INSERT, result.givenUp.get(i), result.causes.get(i));
            }
        }
        logs.putBack(unwritten);
        return unwritten.size();
    }

    /**
     * Writes one batch of changes or logs to {@code target}, the mapper or writer, which stands for the database or
     * service it writes to. A batch that fails for a reason {@code isRetryable} names is kept whole. Any other failure
     * may be a row's or the database's, so the batch is written again one row at a time: when some are written the
     * database works, and those that fail are given up; when none is written, all are kept.
     */
    private <T> BatchResult<T> writeBatch(Round round, Object target, Predicate<Throwable> isRetryable, BatchWrite<T> write, List<T> batch) {
        BatchResult<T> result = new BatchResult<>();
        if (round.shouldStop(target)) {
            result.kept = batch;
            return result;
        }
        Throwable failure = failureOf(write, batch);
        if (failure == null) {
            round.resetFailures(target);
            round.written.addAndGet(batch.size());
            result.written = batch;
            return result;
        }
        result.failures++;
        if (isRetryable.test(failure)) {
            round.countFailure(target);
            result.kept = batch;
            return result;
        }
        round.resetFailures(target);   // the database answered
        List<T> written = new ArrayList<>(), kept = new ArrayList<>(), failed = new ArrayList<>();
        List<Throwable> causes = new ArrayList<>();
        if (batch.size() == 1) {
            failed.add(batch.getFirst());
            causes.add(failure);
        } else {
            for (T one : batch) {
                if (round.shouldStop(target)) {
                    kept.add(one);
                    continue;
                }
                Throwable failureOfOne = failureOf(write, List.of(one));
                if (failureOfOne == null) {
                    round.resetFailures(target);
                    round.written.incrementAndGet();
                    written.add(one);
                    continue;
                }
                result.failures++;
                if (isRetryable.test(failureOfOne)) {
                    round.countFailure(target);
                    kept.add(one);
                } else {
                    round.resetFailures(target);
                    failed.add(one);
                    causes.add(failureOfOne);
                }
            }
        }
        result.written = written;
        result.kept = kept;
        if (!written.isEmpty()) {
            result.givenUp = failed;
            result.causes = causes;
        } else if (!failed.isEmpty()) {
            // Nothing of the batch was written, so its failures tell nothing about the rows: they wait as well.
            Throwable first = causes.getFirst();
            LOG.log(System.Logger.Level.WARNING, () -> "None of a batch of " + batch.size() + " could be written through "
                    + target.getClass().getName() + "; kept for the next round. Its first failure: " + first);
            kept.addAll(failed);
        }
        return result;
    }

    /** Why writing {@code rows} failed, or null when they are written. */
    private static <T> Throwable failureOf(BatchWrite<T> write, List<T> rows) {
        try {
            write.write(rows);
            return null;
        } catch (Throwable e) {
            return e;
        }
    }
}
