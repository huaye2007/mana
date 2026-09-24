package cn.managame.core.write;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.metadata.EntityMetadata;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/** Table-affine save workers with merging double buffers for entities or FIFO queues for logs. */
public final class WriteBehindEngine implements AutoCloseable {
    private final Object closeMonitor = new Object();
    private final DataAccess dataAccess;
    private final int queueCapacityPerTable;
    private final int batchSize;
    private final long flushIntervalNanos;
    private final int maxRetries;
    private final long idleTimeoutNanos;
    private final WriteFailureHandler failureHandler;
    private final boolean appendOnly;
    private final Thread[] workers;
    private final AtomicReferenceArray<Throwable> workerFailures;
    private volatile boolean accepting = true;
    private volatile boolean stopping;
    private final ConcurrentHashMap<String, Writer> writers = new ConcurrentHashMap<>();

    public WriteBehindEngine(DataAccess access, int capacity, int batchSize, Duration interval,
                             int retries, WriteFailureHandler handler) {
        this(access, capacity, batchSize, interval, retries, handler, Duration.ZERO);
    }

    public WriteBehindEngine(DataAccess access, int capacity, int batchSize, Duration interval,
                             int retries, WriteFailureHandler handler, Duration idleTimeout) {
        this(access, capacity, batchSize, interval, retries, handler, idleTimeout, 2, false);
    }

    /** Fixed worker count; a physical writer key always hashes to the same worker. */
    public WriteBehindEngine(DataAccess access, int capacity, int batchSize, Duration interval,
                             int retries, WriteFailureHandler handler, Duration idleTimeout, int workerCount) {
        this(access, capacity, batchSize, interval, retries, handler, idleTimeout, workerCount, false);
    }

    /** Separate log receive path: one bounded FIFO per table, drained directly to INSERT batches. */
    public static WriteBehindEngine logs(DataAccess access, int capacity, int batchSize, Duration interval,
                                        int retries, WriteFailureHandler handler, Duration idleTimeout, int workerCount) {
        return new WriteBehindEngine(access, capacity, batchSize, interval, retries, handler,
                idleTimeout, workerCount, true);
    }

    private WriteBehindEngine(DataAccess access, int capacity, int batchSize, Duration interval,
                              int retries, WriteFailureHandler handler, Duration idleTimeout,
                              int workerCount, boolean appendOnly) {
        this.dataAccess = Objects.requireNonNull(access);
        if (capacity <= 0 || batchSize <= 0 || workerCount <= 0) {
            throw new IllegalArgumentException("capacity, batchSize and workerCount must be > 0");
        }
        if (Objects.requireNonNull(idleTimeout).isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be >= 0");
        }
        this.queueCapacityPerTable = capacity;
        this.batchSize = Math.min(batchSize, Math.max(1, access.maxBatchSize()));
        this.flushIntervalNanos = Math.max(1, Objects.requireNonNull(interval).toNanos());
        this.idleTimeoutNanos = idleTimeout.toNanos();
        this.maxRetries = Math.max(0, retries);
        this.failureHandler = handler == null ? WriteFailureHandler.stderr() : handler;
        this.appendOnly = appendOnly;
        this.workers = new Thread[workerCount];
        this.workerFailures = new AtomicReferenceArray<>(workerCount);
    }

    public void submit(WriteOperation<?> operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.type() == WriteType.DELETE_INSERT || (appendOnly && operation.type() != WriteType.INSERT)) {
            throw new IllegalArgumentException("Unsupported submission: " + operation.type());
        }
        checkAccepting();
        String key = Objects.requireNonNull(dataAccess.writerKey(operation.metadata(), operation.physicalName()));
        for (;;) {
            Writer writer = writers.get(key);
            if (writer == null) {
                synchronized (writers) {
                    checkAccepting();
                    writer = writers.computeIfAbsent(key,
                            ignored -> appendOnly
                                    ? new LogWriter(operation.metadata(), key, operation.physicalName() != null)
                                    : new EntityWriter(operation.metadata(), key, operation.physicalName() != null));
                    startWorkers();
                }
            }
            if (writer.metadata.type() != operation.metadata().type()) {
                throw new DataException("Two entity types resolved to the same physical writer key: " + key);
            }
            if (writer.enqueue(operation)) return;
        }
    }

    // Called under the registration monitor. Engines with no submissions create no threads.
    private void startWorkers() {
        if (workers[0] != null) return;
        for (int i = 0; i < workers.length; i++) {
            int shard = i;
            workers[i] = Thread.ofVirtual().name("game-data-" + (appendOnly ? "log-" : "write-") + i)
                    .unstarted(() -> runWorker(shard));
        }
        for (Thread worker : workers) worker.start();
    }

    private void runWorker(int shard) {
        for (;;) {
            boolean progressed = false;
            for (Writer writer : writers.values()) {
                if (writer.shard != shard) continue;
                try { progressed |= writer.saveIfReady(); }
                catch (Throwable failure) { writer.failTerminal(failure); }
            }
            if (stopping) {
                boolean drained = true;
                for (Writer writer : writers.values()) {
                    if (writer.shard != shard) continue;
                    synchronized (writer.monitor) {
                        if (writer.pending != 0 && writer.state != WriterState.FAILED) drained = false;
                        else if (writer.state == WriterState.RUNNING) writer.state = WriterState.CLOSED;
                    }
                }
                if (drained) return;
            }
            if (Thread.interrupted()) {
                DataException failure = new DataException("Save worker interrupted: " + shard);
                workerFailures.set(shard, failure);
                for (Writer writer : writers.values()) {
                    if (writer.shard == shard) writer.failTerminal(failure);
                }
                return;
            }
            if (progressed) continue;
            LockSupport.parkNanos(this, Math.max(TimeUnit.MILLISECONDS.toNanos(1),
                    Math.min(flushIntervalNanos, TimeUnit.MILLISECONDS.toNanos(10))));
        }
    }

    private void checkAccepting() {
        if (!accepting) throw new IllegalStateException("write engine is closing/closed");
    }

    /** Tables finish submissions already admitted, including producers waiting for capacity. */
    public void stopAccepting() {
        synchronized (writers) {
            accepting = false;
            for (Thread worker : workers) if (worker != null) LockSupport.unpark(worker);
        }
    }

    public void flush() {
        checkNotWriterThread();
        for (Writer writer : List.copyOf(writers.values())) writer.flush();
    }

    public void flush(Class<?> entityType) {
        checkNotWriterThread();
        for (Writer writer : List.copyOf(writers.values())) {
            if (writer.metadata.type() == entityType) writer.flush();
        }
    }

    public WriterState state(Class<?> entityType) {
        List<Writer> matched = writers.values().stream()
                .filter(w -> w.metadata.type() == entityType).toList();
        if (matched.isEmpty()) return accepting ? WriterState.RUNNING : WriterState.CLOSED;
        if (matched.stream().anyMatch(w -> w.state == WriterState.FAILED)) return WriterState.FAILED;
        if (matched.stream().allMatch(w -> w.state == WriterState.CLOSED)) return WriterState.CLOSED;
        return WriterState.RUNNING;
    }

    int writerCount() { return writers.size(); }

    /** Blocking lifecycle calls from any save worker would deadlock its assigned tables. */
    public void checkNotWriterThread() {
        synchronized (writers) {
            for (Thread worker : workers) {
                if (Thread.currentThread() == worker) {
                    throw new IllegalStateException("Cannot wait for a write engine from its own save worker");
                }
            }
        }
    }

    @Override public void close() {
        checkNotWriterThread();
        synchronized (closeMonitor) {
            stopAccepting();
            // Complete the admission boundary before workers are allowed to exit. A producer
            // which passed checkAccepting while holding this monitor has already incremented pending.
            for (Writer writer : writers.values()) {
                synchronized (writer.monitor) { writer.monitor.notifyAll(); }
            }
            stopping = true;
            boolean interrupted = false;
            try {
                for (Thread worker : workers) {
                    if (worker == null) continue;
                    LockSupport.unpark(worker);
                    while (worker.isAlive()) {
                        try { worker.join(); }
                        catch (InterruptedException e) { interrupted = true; }
                    }
                }
                RuntimeException failure = null;
                for (Writer writer : writers.values()) {
                    try { writer.checkTerminalFailure(); }
                    catch (RuntimeException e) {
                        if (failure == null) failure = e;
                        else failure.addSuppressed(e);
                    }
                }
                if (failure != null) throw failure;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private abstract class Writer {
        private final EntityMetadata<?> metadata;
        private final String writerKey;
        private final boolean routed;
        private final int shard;
        // The only table lock: admission, merge/swap or queue drain, pending waits and retirement.
        private final Object monitor = new Object();
        private long readyAt;
        private long lastActivityNanos = System.nanoTime();
        private int flushWaiters;
        private boolean retired;
        private long pending;
        private volatile WriterState state = WriterState.RUNNING;
        private volatile Throwable terminalFailure;

        Writer(EntityMetadata<?> metadata, String writerKey, boolean routed) {
            this.metadata = metadata;
            this.writerKey = writerKey;
            this.routed = routed;
            this.shard = Math.floorMod(writerKey.hashCode(), workers.length);
        }

        abstract int size();
        abstract long queuedSubmissions();
        abstract boolean canAdd(WriteOperation<?> operation);
        abstract void add(WriteOperation<?> operation);
        abstract SaveBatch detach();

        boolean enqueue(WriteOperation<?> operation) {
            synchronized (monitor) {
                checkAccepting();
                if (retired) return false;
                checkTerminalFailure();
                pending++;
                boolean published = false;
                try {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    while (!canAdd(operation)) {
                        // Save callbacks must not form capacity waits between worker shards.
                        for (Thread worker : workers) {
                            if (Thread.currentThread() == worker) {
                                throw new IllegalStateException("Cannot wait for buffer space from a save worker");
                            }
                        }
                        monitor.wait();
                        checkTerminalFailure();
                    }
                    if (size() == 0) readyAt = System.nanoTime() + flushIntervalNanos;
                    add(operation);
                    published = true;
                    LockSupport.unpark(workers[shard]);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DataException("Interrupted while enqueueing write for " + writerKey, e);
                } finally {
                    if (!published) pending--;
                    monitor.notifyAll();
                }
            }
        }

        boolean saveIfReady() {
            SaveBatch batch;
            synchronized (monitor) {
                if (state != WriterState.RUNNING || retired) return false;
                if (pending == 0) {
                    if (stopping) { state = WriterState.CLOSED; monitor.notifyAll(); }
                    else retireIfIdle();
                    return false;
                }
                int queued = size();
                if (queuedSubmissions() == 0 || (queued > 0 && queued < batchSize && flushWaiters == 0 && accepting
                        && System.nanoTime() - readyAt < 0)) return false;
                batch = detach();
                monitor.notifyAll();
            }
            try { batch.save().run(); }
            finally {
                batch.clear().run();
                synchronized (monitor) {
                    pending -= batch.submissions();
                    lastActivityNanos = System.nanoTime();
                    monitor.notifyAll();
                }
            }
            return true;
        }

        private void retireIfIdle() {
            if (!routed || idleTimeoutNanos == 0 || !accepting
                    || System.nanoTime() - lastActivityNanos < idleTimeoutNanos) return;
            retired = true;
            state = WriterState.CLOSED;
            writers.remove(writerKey, this);
        }
        final boolean process(List<WriteOperation<?>> batch) {
            if (batch.isEmpty()) return true;
            try {
                BatchResult result = persist(batch);
                if (result.allSuccess()) return true;
                throw new BatchWriteException("Batch completed with non-successful operations: writer=" + writerKey, result);
            } catch (BatchWriteException e) {
                reportFailure(e.result() == null ? batch : e.result().problemOperations(batch), e);
            } catch (Throwable e) {
                reportFailure(batch, e);
            }
            return false;
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

        final void reportFailure(List<WriteOperation<?>> operations, Throwable error) {
            if (operations == null || operations.isEmpty()) return;
            try { failureHandler.onFailure(List.copyOf(operations), error); }
            catch (Throwable handlerError) {
                System.err.println("[game-data] failure handler threw; falling back to stderr");
                handlerError.printStackTrace(System.err);
                try { WriteFailureHandler.stderr().onFailure(operations, error); }
                catch (Throwable loggingError) { error.printStackTrace(System.err); }
            }
        }

        private void failTerminal(Throwable error) {
            synchronized (monitor) {
                terminalFailure = error;
                state = WriterState.FAILED;
                monitor.notifyAll();
            }
        }

        private void flush() {
            synchronized (monitor) {
                checkTerminalFailure();
                flushWaiters++;
                LockSupport.unpark(workers[shard]);
                try {
                    while (pending > 0) {
                        checkTerminalFailure();
                        try { monitor.wait(); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new DataException("Interrupted while flushing " + writerKey, e);
                        }
                    }
                    checkTerminalFailure();
                } finally { flushWaiters--; }
            }
        }

        private void checkTerminalFailure() {
            Throwable failure = terminalFailure == null ? workerFailures.get(shard) : terminalFailure;
            if (failure != null) {
                state = WriterState.FAILED;
                throw new DataException("Writer terminated unexpectedly: " + writerKey, failure);
            }
        }
    }

    /** A detached batch owns its payload until save and clear complete on the assigned worker. */
    private record SaveBatch(long submissions, Runnable save, Runnable clear) { }

    private final class EntityWriter extends Writer {
        private final DoubleWriteBuffer buffers = new DoubleWriteBuffer();

        EntityWriter(EntityMetadata<?> metadata, String key, boolean routed) { super(metadata, key, routed); }
        @Override int size() { return buffers.active().size(); }
        @Override long queuedSubmissions() { return buffers.active().submissions(); }
        @Override boolean canAdd(WriteOperation<?> operation) {
            return buffers.active().canAdd(operation, queueCapacityPerTable);
        }
        @Override void add(WriteOperation<?> operation) { buffers.active().add(operation); }
        @Override SaveBatch detach() {
            WriteBuffer buffer = buffers.swap();
            return new SaveBatch(buffer.submissions(), () -> save(buffer), buffer::clear);
        }

        private void save(WriteBuffer buffer) {
            List<WriteOperation<?>> batch = new ArrayList<>(batchSize);
            buffer.forEach(operation -> {
                if (operation.type() == WriteType.DELETE_INSERT) {
                    // Never split the replacement across unrelated batches. A size-one backend
                    // needs two calls, and the INSERT is skipped if its DELETE failed.
                    if (!batch.isEmpty()) { process(batch); batch.clear(); }
                    var deletion = operation.withType(WriteType.DELETE);
                    var insertion = operation.withType(WriteType.INSERT);
                    if (batchSize >= 2) process(List.of(deletion, insertion));
                    else if (process(List.of(deletion))) process(List.of(insertion));
                    else reportFailure(List.of(insertion), new DataException("INSERT skipped after failed DELETE"));
                } else {
                    batch.add(operation);
                    if (batch.size() == batchSize) { process(batch); batch.clear(); }
                }
            });
            if (!batch.isEmpty()) process(batch);
        }

    }

    private final class LogWriter extends Writer {
        private final LogWriteQueue queue = new LogWriteQueue();

        LogWriter(EntityMetadata<?> metadata, String key, boolean routed) { super(metadata, key, routed); }
        @Override int size() { return queue.size(); }
        @Override long queuedSubmissions() { return queue.size(); }
        @Override boolean canAdd(WriteOperation<?> operation) { return queue.size() < queueCapacityPerTable; }
        @Override void add(WriteOperation<?> operation) { queue.add(operation); }
        @Override SaveBatch detach() {
            List<WriteOperation<?>> batch = queue.drain(batchSize);
            return new SaveBatch(batch.size(), () -> process(batch), batch::clear);
        }
    }
}