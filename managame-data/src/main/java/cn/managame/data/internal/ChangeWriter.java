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
import java.util.function.IntSupplier;

/**
 * Writes every entity type's changes through its {@link EntityMapper} and every log type's logs through its
 * {@link LogWriter}, in rounds. In a round each change set and log queue with something waiting is written by one of
 * the write threads, so different types are written at once while one type is written by one thread at a time, in the
 * order its changes were made. A type's deletes are written first, so a row can take the unique value of one deleted
 * in the same round, then replacements, inserts and updates, in batches. Rounds never overlap.
 *
 * <p>A failure the mapper or writer calls retryable leaves the batch for the next round, and a mapper or writer that
 * failed so twice in a row gets nothing more in the round, which would only wait out the same timeout again. Any other
 * failure splits the batch into single changes or logs; one that still fails goes to the DataErrorHandler and is dropped.
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
    /** Held while the DataErrorHandler runs, so it is never called twice at once. */
    private final Object errorHandlerLock = new Object();

    /** What one round left: changes kept for a later round, changes dropped, and each target's failures in a row. */
    public static final class Round {
        private final boolean hasDeadline;
        private final long deadline;
        /** By mapper or writer, each standing for the database or service it writes to. */
        private final ConcurrentHashMap<Object, AtomicInteger> failuresInARow = new ConcurrentHashMap<>();
        private final AtomicInteger unwritten = new AtomicInteger(), dropped = new AtomicInteger();

        private Round(boolean hasDeadline, long deadline) {
            this.hasDeadline = hasDeadline;
            this.deadline = deadline;
        }

        /** Changes and logs kept for a later round after retryable failures. */
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
                for (PendingChange change : waiting)
                    reportFailure(null, DATA_SAVE_FAILED, changes.type.entityClass(), change.operation, change.entityOrId(), cause);
                changes.putBack(List.of());
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
        synchronized (errorHandlerLock) {
            try {
                errorHandler.onError(new DataFailure(errorCode, type, operation, value, cause));
            } catch (Throwable e) {
                LOG.log(System.Logger.Level.ERROR, "DataErrorHandler failed", e);
            }
        }
    }

    private Round writeAll(boolean hasDeadline, long deadline) {
        synchronized (roundLock) {
            Round round = new Round(hasDeadline, deadline);
            List<IntSupplier> writes = new ArrayList<>();
            for (ChangeSet changes : changeSets) if (!changes.isEmpty()) writes.add(() -> writeChanges(changes, round));
            for (LogQueue logs : logQueues) if (!logs.isEmpty()) writes.add(() -> writeLogs(logs, round));
            if (writePool == null || writes.size() < 2) {
                for (IntSupplier write : writes) run(write, round);
                return round;
            }
            List<Future<?>> futures = new ArrayList<>(writes.size());
            for (IntSupplier write : writes) futures.add(writePool.submit(() -> run(write, round)));
            // Waits for every write even when interrupted: a round that returned early would let the next one write
            // a type this one is still writing.
            boolean interrupted = false;
            for (Future<?> future : futures) {
                while (true) {
                    try {
                        future.get();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException e) {
                        break;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return round;
        }
    }

    private static void run(IntSupplier write, Round round) {
        try {
            round.unwritten.addAndGet(write.getAsInt());
        } catch (Throwable e) {
            round.dropped.incrementAndGet();
            LOG.log(System.Logger.Level.ERROR, "writing data failed", e);
        }
    }

    /** Writes one entity type's changes; returns how many it put back for a later round. */
    private int writeChanges(ChangeSet changes, Round round) {
        List<PendingChange> taken = changes.takeAll(), unwritten = new ArrayList<>();
        boolean finished = false;
        try {
            Map<DataOperation, List<PendingChange>> byOperation = new EnumMap<>(DataOperation.class);
            for (PendingChange change : taken) byOperation.computeIfAbsent(change.operation, key -> new ArrayList<>()).add(change);
            for (DataOperation operation : WRITE_ORDER) {
                List<PendingChange> batch = byOperation.getOrDefault(operation, List.of());
                for (int from = 0; from < batch.size(); from += batchSize)
                    writeBatch(changes, round, operation, batch.subList(from, Math.min(batch.size(), from + batchSize)), unwritten);
            }
            finished = true;
        } finally {
            // After an unexpected failure it is unknown which changes were written: all are written again, which does
            // no harm, as an insert comes back as DELETE_INSERT.
            if (!finished) unwritten = taken;
            for (PendingChange conflict : changes.putBack(unwritten)) {
                changes.dropped.incrementAndGet();
                reportFailure(round, DATA_SAVE_FAILED, changes.type.entityClass(), conflict.operation, conflict.entityOrId(),
                        new IllegalStateException("a change that failed to be written conflicts with a later change of its row"));
            }
        }
        return unwritten.size();
    }

    private void writeBatch(ChangeSet changes, Round round, DataOperation operation, List<PendingChange> batch, List<PendingChange> unwritten) {
        EntityMapper mapper = changes.mapper;
        if (round.shouldStop(mapper)) {
            unwritten.addAll(batch);
            return;
        }
        try {
            mapper.write(changes.type, operation, batch);
            round.resetFailures(mapper);
            changes.written.addAndGet(batch.size());
            return;
        } catch (Throwable e) {
            changes.failures.incrementAndGet();
            if (mapper.isRetryable(e)) {
                round.countFailure(mapper);
                unwritten.addAll(batch);
                return;
            }
            round.resetFailures(mapper);   // the database answered, so what failed is the data
            if (batch.size() == 1) {
                changes.dropped.incrementAndGet();
                reportFailure(round, DATA_SAVE_FAILED, changes.type.entityClass(), operation, batch.getFirst().entityOrId(), e);
                return;
            }
        }
        for (PendingChange change : batch) {
            if (round.shouldStop(mapper)) {
                unwritten.add(change);
                continue;
            }
            try {
                mapper.write(changes.type, operation, List.of(change));
                round.resetFailures(mapper);
                changes.written.incrementAndGet();
            } catch (Throwable e) {
                changes.failures.incrementAndGet();
                if (mapper.isRetryable(e)) {
                    round.countFailure(mapper);
                    unwritten.add(change);
                } else {
                    round.resetFailures(mapper);
                    changes.dropped.incrementAndGet();
                    reportFailure(round, DATA_SAVE_FAILED, changes.type.entityClass(), operation, change.entityOrId(), e);
                }
            }
        }
    }

    /**
     * Writes what was queued when it started, so logs queued meanwhile wait for the next round. Logs not written after a
     * retryable failure go back to the end of the queue; returns how many.
     */
    private int writeLogs(LogQueue logs, Round round) {
        List<Object> unwritten = new ArrayList<>();
        int remaining = logs.size();
        while (remaining > 0 && !round.shouldStop(logs.writer)) {
            List<Object> batch = new ArrayList<>(Math.min(batchSize, remaining));
            for (Object log; batch.size() < batchSize && remaining > 0 && (log = logs.poll()) != null; remaining--) batch.add(log);
            if (batch.isEmpty()) break;
            writeLogBatch(logs, round, batch, unwritten);
        }
        logs.putBack(unwritten);
        return unwritten.size();
    }

    private void writeLogBatch(LogQueue logs, Round round, List<Object> batch, List<Object> unwritten) {
        LogWriter writer = logs.writer;
        if (round.shouldStop(writer)) {
            unwritten.addAll(batch);
            return;
        }
        try {
            writer.write(logs.type, batch);
            round.resetFailures(writer);
            logs.written.addAndGet(batch.size());
            return;
        } catch (Throwable e) {
            logs.failures.incrementAndGet();
            if (writer.isRetryable(e)) {
                round.countFailure(writer);
                unwritten.addAll(batch);
                return;
            }
            round.resetFailures(writer);
            if (batch.size() == 1) {
                logs.dropped.incrementAndGet();
                reportFailure(round, DATA_LOG_SAVE_FAILED, logs.type, LOG_INSERT, batch.getFirst(), e);
                return;
            }
        }
        for (Object log : batch) {
            if (round.shouldStop(writer)) {
                unwritten.add(log);
                continue;
            }
            try {
                writer.write(logs.type, List.of(log));
                round.resetFailures(writer);
                logs.written.incrementAndGet();
            } catch (Throwable e) {
                logs.failures.incrementAndGet();
                if (writer.isRetryable(e)) {
                    round.countFailure(writer);
                    unwritten.add(log);
                } else {
                    round.resetFailures(writer);
                    logs.dropped.incrementAndGet();
                    reportFailure(round, DATA_LOG_SAVE_FAILED, logs.type, LOG_INSERT, log, e);
                }
            }
        }
    }
}
