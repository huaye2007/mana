package cn.managame.core.write;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.metadata.EntityMetadata;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Physical-table-affine asynchronous write-behind.
 *
 * All operations that DataAccess resolves to the same writer key are persisted by one virtual
 * thread. Different physical tables/collections run in parallel. Values are read from live entity
 * references only when the batch executes; no entity snapshot is created.
 */
public final class WriteBehindEngine implements AutoCloseable {
    private final Object closeMonitor = new Object();
    private final Object metricsMonitor = new Object();
    private final DataAccess dataAccess;
    private final int queueCapacityPerTable;
    private final int batchSize;
    private final long flushIntervalNanos;
    private final int maxRetries;
    private final long idleTimeoutNanos;
    private final WriteFailureHandler failureHandler;
    private volatile boolean accepting = true;
    private final ConcurrentHashMap<String, TableWriter> writers = new ConcurrentHashMap<>();
    // One numeric summary per entity, not one retained entry per historical partition.
    private final Map<Class<?>, TableWriterMetrics> retiredMetrics = new HashMap<>();

    public WriteBehindEngine(
            DataAccess dataAccess,
            int queueCapacityPerTable,
            int batchSize,
            Duration flushInterval,
            int maxRetries,
            WriteFailureHandler failureHandler) {
        this(dataAccess, queueCapacityPerTable, batchSize, flushInterval, maxRetries, failureHandler, Duration.ZERO);
    }

    /** A positive idle timeout retires empty routed-table writers; zero disables retirement. */
    public WriteBehindEngine(
            DataAccess dataAccess,
            int queueCapacityPerTable,
            int batchSize,
            Duration flushInterval,
            int maxRetries,
            WriteFailureHandler failureHandler,
            Duration idleTimeout) {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative()) throw new IllegalArgumentException("idleTimeout must be >= 0");
        this.idleTimeoutNanos = idleTimeout.toNanos();
        this.dataAccess = Objects.requireNonNull(dataAccess);
        if (queueCapacityPerTable <= 0) throw new IllegalArgumentException("queueCapacityPerTable must be > 0");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be > 0");
        this.queueCapacityPerTable = queueCapacityPerTable;
        this.batchSize = Math.min(batchSize, Math.max(1, dataAccess.maxBatchSize()));
        this.flushIntervalNanos = Math.max(1L, Objects.requireNonNull(flushInterval).toNanos());
        this.maxRetries = Math.max(0, maxRetries);
        this.failureHandler = failureHandler == null ? WriteFailureHandler.stderr() : failureHandler;
    }

    public void submit(WriteOperation<?> op) {
        Objects.requireNonNull(op, "op");
        checkAccepting();
        String writerKey = dataAccess.writerKey(op.metadata(), op.physicalName());
        for (;;) {
            TableWriter writer = writers.get(writerKey);
            if (writer == null) {
                // Only writer registration shares a monitor with shutdown; ordinary submissions do not.
                synchronized (writers) {
                    checkAccepting();
                    writer = writers.computeIfAbsent(writerKey,
                            ignored -> new TableWriter(op.metadata(), writerKey, op.physicalName() != null));
                }
            }
            if (writer.metadata.type() != op.metadata().type()) {
                throw new DataException("Two entity types resolved to the same physical writer key: " + writerKey);
            }
            if (writer.enqueue(op)) return;
            // An idle writer can retire between lookup and enqueue; retry with its replacement.
        }
    }

    private void checkAccepting() {
        if (!accepting) throw new IllegalStateException("write engine is closing/closed");
    }

    /** Stops new submissions; each table drains operations it has already accepted. */
    public void stopAccepting() {
        synchronized (writers) { accepting = false; }
    }

    public void flush() {
        for (TableWriter writer : List.copyOf(writers.values())) writer.flush();
    }

    public void flush(Class<?> entityType) {
        for (TableWriter writer : List.copyOf(writers.values())) {
            if (writer.metadata.type() == entityType) writer.flush();
        }
    }

    public TableWriterState state(Class<?> entityType) {
        List<TableWriter> matched = writers.values().stream()
                .filter(w -> w.metadata.type() == entityType).toList();
        if (matched.isEmpty()) return accepting ? TableWriterState.RUNNING : TableWriterState.CLOSED;
        if (matched.stream().anyMatch(w -> w.state == TableWriterState.FAILED)) return TableWriterState.FAILED;
        if (matched.stream().allMatch(w -> w.state == TableWriterState.CLOSED)) return TableWriterState.CLOSED;
        return TableWriterState.RUNNING;
    }

    public TableWriterMetrics metrics(Class<?> entityType) {
        synchronized (metricsMonitor) {
            TableWriterMetrics result = retiredMetrics.getOrDefault(entityType, TableWriterMetrics.empty());
            for (TableWriter writer : writers.values()) {
                if (writer.metadata.type() == entityType) result = mergeMetrics(result, writer.metrics());
            }
            return new TableWriterMetrics(state(entityType), result.queueSize(), result.pendingCount(),
                    result.successfulBatches(), result.successfulOperations(), result.failedBatches(),
                    result.lastBatchSize(), result.lastFlushNanos(), result.maxFlushNanos(), result.lastFailureEpochMillis());
        }
    }

    private static TableWriterMetrics mergeMetrics(TableWriterMetrics a, TableWriterMetrics b) {
        return new TableWriterMetrics(TableWriterState.RUNNING, a.queueSize() + b.queueSize(),
                a.pendingCount() + b.pendingCount(), a.successfulBatches() + b.successfulBatches(),
                a.successfulOperations() + b.successfulOperations(), a.failedBatches() + b.failedBatches(),
                Math.max(a.lastBatchSize(), b.lastBatchSize()), Math.max(a.lastFlushNanos(), b.lastFlushNanos()),
                Math.max(a.maxFlushNanos(), b.maxFlushNanos()), Math.max(a.lastFailureEpochMillis(), b.lastFailureEpochMillis()));
    }

    public Map<String, TableWriterMetrics> metricsByPhysicalWriter() {
        Map<String, TableWriterMetrics> result = new LinkedHashMap<>();
        writers.forEach((key, value) -> result.put(key, value.metrics()));
        return Map.copyOf(result);
    }

    public int tableWriterCount() { return writers.size(); }

    /** Reject lifecycle waits from this engine's own database/failure callbacks. */
    public void checkNotWriterThread() {
        for (TableWriter writer : writers.values()) {
            if (Thread.currentThread() == writer.thread) {
                throw new IllegalStateException("Cannot close a write engine from its own writer callback");
            }
        }
    }

    @Override
    public void close() {
        checkNotWriterThread();
        synchronized (closeMonitor) {
            closeWriters();
        }
    }

    private void closeWriters() {
        stopAccepting();
        List<TableWriter> closing = List.copyOf(writers.values());
        for (TableWriter writer : closing) writer.stopAccepting();
        RuntimeException failure = null;
        for (TableWriter writer : closing) {
            try { writer.join(); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }

    private final class TableWriter {
        private final EntityMetadata<?> metadata;
        private final String writerKey;
        private final boolean routed;
        private long lastActivityNanos = System.nanoTime();
        private final Object bufferMonitor = new Object();
        private ArrayList<WriteOperation<?>> incoming = new ArrayList<>(Math.min(queueCapacityPerTable, batchSize));
        private ArrayList<WriteOperation<?>> spare = new ArrayList<>(Math.min(queueCapacityPerTable, batchSize));
        private volatile int queued;
        private final AtomicLong pending = new AtomicLong();
        private final Object monitor = new Object();
        private final Thread thread;
        private volatile TableWriterState state = TableWriterState.RUNNING;
        private volatile Throwable terminalFailure;
        private volatile boolean stopRequested;

        private volatile long successfulBatches;
        private volatile long successfulOperations;
        private volatile long failedBatches;
        private volatile int lastBatchSize;
        private volatile long lastFlushNanos;
        private volatile long maxFlushNanos;
        private volatile long lastFailureEpochMillis;

        private TableWriter(EntityMetadata<?> metadata, String writerKey, boolean routed) {
            this.metadata = metadata;
            this.writerKey = writerKey;
            this.routed = routed;
            String safe = writerKey.replaceAll("[^A-Za-z0-9_.-]", "_");
            this.thread = Thread.ofVirtual().name("game-data-write-" + safe).start(this::runLoop);
        }

        private boolean enqueue(WriteOperation<?> operation) {
            synchronized (bufferMonitor) {
                checkAccepting();
                if (stopRequested) return false;
                checkTerminalFailure();
                pending.incrementAndGet();
                boolean published = false;
                try {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    while (incoming.size() >= queueCapacityPerTable) {
                        bufferMonitor.wait();
                        checkTerminalFailure();
                    }
                    incoming.add(operation);
                    queued = incoming.size();
                    published = true;
                    bufferMonitor.notifyAll();
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DataException("Interrupted while enqueueing write for " + writerKey, e);
                } finally {
                    if (!published) { pending.decrementAndGet(); signal(); }
                }
            }
        }

        private void runLoop() {
            try {
                while (!stopRequested || pending.get() != 0) {
                    List<WriteOperation<?>> draining = takeBuffer();
                    if (draining == null) {
                        if (retireIfIdle()) return;
                        continue;
                    }
                    // Only this thread owns the drained buffer; producers continue on the other buffer.
                    try {
                        for (int start = 0; start < draining.size(); start += batchSize) {
                            process(draining.subList(start, Math.min(draining.size(), start + batchSize)));
                        }
                    } finally { draining.clear(); }
                }
                state = TableWriterState.CLOSED;
                signal();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failTerminal(new DataException("Table writer interrupted: " + writerKey, e));
            } catch (Throwable e) {
                if (terminalFailure == null) failTerminal(e);
            }
        }

        private boolean retireIfIdle() {
            if (!routed || idleTimeoutNanos == 0 || pending.get() != 0 || !accepting
                    || System.nanoTime() - lastActivityNanos < idleTimeoutNanos) return false;
            synchronized (bufferMonitor) {
                if (!accepting || stopRequested || pending.get() != 0 || !incoming.isEmpty()) return false;
                synchronized (metricsMonitor) {
                    retiredMetrics.merge(metadata.type(), metrics(), WriteBehindEngine::mergeMetrics);
                    stopRequested = true;
                    state = TableWriterState.CLOSED;
                    writers.remove(writerKey, this);
                }
            }
            signal();
            return true;
        }

        private void process(List<WriteOperation<?>> raw) {
            List<WriteOperation<?>> compacted = compact(raw);
            long start = System.nanoTime();
            try {
                BatchResult result = persist(compacted);
                long elapsed = System.nanoTime() - start;
                lastBatchSize = compacted.size();
                lastFlushNanos = elapsed;
                if (elapsed > maxFlushNanos) maxFlushNanos = elapsed;

                if (result.allSuccess()) {
                    successfulBatches++;
                    successfulOperations += raw.size();
                } else {
                    failedBatches++;
                    successfulOperations += result.successCount();
                    lastFailureEpochMillis = System.currentTimeMillis();
                    BatchWriteException error = new BatchWriteException(
                            "Batch completed with non-successful operations: writer=" + writerKey, result);
                    reportFailure(result.problemOperations(compacted), error);
                }
            } catch (BatchWriteException e) {
                failedBatches++;
                lastFailureEpochMillis = System.currentTimeMillis();
                if (e.result() != null) successfulOperations += e.result().successCount();
                List<WriteOperation<?>> problems = e.result() == null
                        ? compacted : e.result().problemOperations(compacted);
                reportFailure(problems, e);
            } catch (Throwable e) {
                failedBatches++;
                lastFailureEpochMillis = System.currentTimeMillis();
                reportFailure(compacted, e);
            } finally {
                lastActivityNanos = System.nanoTime();
                pending.addAndGet(-raw.size());
                signal();
            }
        }

        private List<WriteOperation<?>> takeBuffer() throws InterruptedException {
            synchronized (bufferMonitor) {
                if (incoming.isEmpty()) {
                    if (!stopRequested || pending.get() != 0) bufferMonitor.wait(100);
                    if (incoming.isEmpty()) return null;
                }
                ArrayList<WriteOperation<?>> draining = incoming;
                incoming = spare;
                spare = draining;
                queued = 0;
                bufferMonitor.notifyAll();
                long deadline = System.nanoTime() + flushIntervalNanos;
                while (draining.size() < batchSize && !stopRequested && accepting) {
                    if (!incoming.isEmpty()) {
                        // Transfer a complete accumulation, never shift the active list for each individual item.
                        draining.addAll(incoming);
                        incoming.clear();
                        queued = 0;
                        bufferMonitor.notifyAll();
                        continue;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(bufferMonitor, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
                }
                return draining;
            }
        }

        /** DELETE_GROUP is an ordering barrier; entity-state reduction happens between barriers. */
        private List<WriteOperation<?>> compact(List<WriteOperation<?>> operations) {
            if (operations.size() < 2) return operations;
            // Append-only batches need no identity map or entity-state reduction.
            boolean insertsOnly = true;
            for (WriteOperation<?> operation : operations) {
                if (operation.type() != WriteType.INSERT) { insertsOnly = false; break; }
            }
            if (insertsOnly) return operations;
            List<WriteOperation<?>> result = new ArrayList<>(operations.size());
            int start = 0;
            for (int i = 0; i < operations.size(); i++) {
                if (operations.get(i).type() != WriteType.DELETE_GROUP) continue;
                if (start < i) result.addAll(compactEntityOperations(operations.subList(start, i)));
                WriteOperation<?> deleteGroup = operations.get(i);
                if (result.isEmpty() || result.getLast().type() != WriteType.DELETE_GROUP
                        || !Objects.equals(result.getLast().groupKey(), deleteGroup.groupKey())) {
                    result.add(deleteGroup);
                }
                start = i + 1;
            }
            if (start < operations.size()) result.addAll(compactEntityOperations(operations.subList(start, operations.size())));
            return result;
        }

        private List<WriteOperation<?>> compactEntityOperations(List<WriteOperation<?>> operations) {
            if (operations.size() < 2) return new ArrayList<>(operations);
            boolean[] keep = new boolean[operations.size()];
            WriteOperation<?>[] replacement = new WriteOperation<?>[operations.size()];
            Map<Object, Deque<Integer>> effective = new HashMap<>();
            for (int i = 0; i < operations.size(); i++) {
                WriteOperation<?> current = operations.get(i);
                if (current.type() == WriteType.DELETE_GROUP) throw new IllegalStateException("unexpected DELETE_GROUP");
                Deque<Integer> stack = effective.computeIfAbsent(current.id(), ignored -> new ArrayDeque<>());
                Integer prevIndex = stack.peekLast();
                WriteOperation<?> prev = prevIndex == null ? null
                        : (replacement[prevIndex] != null ? replacement[prevIndex] : operations.get(prevIndex));

                if (prev == null) {
                    keep[i] = true;
                    stack.addLast(i);
                    continue;
                }

                if (prev.type() == WriteType.INSERT && current.type() == WriteType.UPDATE) {
                    replacement[prevIndex] = current.asInsert();
                } else if (prev.type() == WriteType.INSERT && current.type() == WriteType.DELETE) {
                    keep[prevIndex] = false;
                    replacement[prevIndex] = null;
                    stack.removeLast();
                } else if (prev.type() == WriteType.UPDATE && current.type() == WriteType.UPDATE) {
                    replacement[prevIndex] = current;
                } else if (prev.type() == WriteType.UPDATE && current.type() == WriteType.DELETE) {
                    keep[prevIndex] = false;
                    replacement[prevIndex] = null;
                    stack.removeLast();
                    keep[i] = true;
                    stack.addLast(i);
                } else if (prev.type() == WriteType.DELETE && current.type() == WriteType.DELETE) {
                    // duplicate delete
                } else {
                    keep[i] = true;
                    stack.addLast(i);
                }
            }

            List<WriteOperation<?>> result = new ArrayList<>();
            for (int i = 0; i < operations.size(); i++) {
                if (!keep[i]) continue;
                result.add(replacement[i] != null ? replacement[i] : operations.get(i));
            }
            return result;
        }

        private BatchResult persist(List<WriteOperation<?>> batch) {
            if (batch.isEmpty()) return BatchResult.success(0);
            int retries = 0;
            while (true) {
                try {
                    return dataAccess.applyBatch(batch);
                } catch (Throwable e) {
                    WriteFailureAction action = dataAccess.classifyWriteFailure(e);
                    if (action != WriteFailureAction.RETRY) {
                        if (e instanceof BatchWriteException batchWriteException) throw batchWriteException;
                        throw asWriteFailure("Unrecoverable write batch failure", e, retries);
                    }
                    if (retries >= maxRetries) {
                        throw asWriteFailure("Write batch still failed after safe retries", e, retries);
                    }
                    retries++;
                    try {
                        Thread.sleep(Math.min(1000L, 50L << Math.min(retries - 1, 4)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new DataException("Interrupted while retrying " + writerKey, ie);
                    }
                }
            }
        }

        private RuntimeException asWriteFailure(String message, Throwable error, int retries) {
            return new DataException(message + ": writer=" + writerKey + ", retries=" + retries, error);
        }

        private void failTerminal(Throwable error) {
            terminalFailure = error;
            state = TableWriterState.FAILED;
            synchronized (bufferMonitor) { bufferMonitor.notifyAll(); }
            signal();
        }

        private void reportFailure(List<WriteOperation<?>> operations, Throwable error) {
            if (operations == null || operations.isEmpty()) return;
            try { failureHandler.onFailure(List.copyOf(operations), error); }
            catch (Throwable handlerError) {
                System.err.println("[game-data] failure handler threw; falling back to stderr");
                handlerError.printStackTrace(System.err);
                try { WriteFailureHandler.stderr().onFailure(operations, error); }
                catch (Throwable loggingError) { error.printStackTrace(System.err); }
            }
        }

        private void flush() {
            if (Thread.currentThread() == thread) throw new IllegalStateException("Cannot flush from writer callback");
            checkTerminalFailure();
            synchronized (monitor) {
                while (pending.get() > 0) {
                    checkTerminalFailure();
                    try { monitor.wait(20L); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new DataException("Interrupted while flushing " + writerKey, e);
                    }
                }
            }
            checkTerminalFailure();
        }

        private TableWriterMetrics metrics() {
            return new TableWriterMetrics(state, queued, pending.get(), successfulBatches,
                    successfulOperations, failedBatches, lastBatchSize, lastFlushNanos,
                    maxFlushNanos, lastFailureEpochMillis);
        }

        private void stopAccepting() {
            synchronized (bufferMonitor) { stopRequested = true; bufferMonitor.notifyAll(); }
        }

        private void join() {
            boolean interrupted = false;
            try {
                while (thread.isAlive()) {
                    try { thread.join(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                checkTerminalFailure();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void checkTerminalFailure() {
            Throwable failure = terminalFailure;
            if (failure != null) throw new DataException("Table writer terminated unexpectedly: " + writerKey, failure);
        }

        private void signal() { synchronized (monitor) { monitor.notifyAll(); } }
    }
}
