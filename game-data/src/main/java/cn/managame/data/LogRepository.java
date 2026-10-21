package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.mysql.MysqlLogWriter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class LogRepository<E> {
    private final ConcurrentLinkedQueue<E> queue = new ConcurrentLinkedQueue<>();
    /** Queue length; ConcurrentLinkedQueue.size() would walk every node. */
    private final AtomicInteger queued = new AtomicInteger();
    private MysqlLogWriter logWriter;
    private WriteBehindManager writer;
    private int capacity;
    protected LogRepository() {}
    final long queuedLogs() { return queued.get(); }
    final void initialize(MysqlLogWriter logWriter, WriteBehindManager writer, int capacity) {
        this.logWriter = logWriter; this.writer = writer; this.capacity = capacity;
    }
    /**
     * Queues a log for the next flush and returns true. When {@code logQueueCapacity} logs are already waiting
     * (for example while the database is down) the log is not accepted: it is reported to DataErrorHandler on
     * this thread as a LOG_INSERT failure and false is returned, so memory stays bounded.
     * The accepted log and its partition fields must not be modified afterwards.
     */
    public final boolean insert(E log) {
        Objects.requireNonNull(log);
        if (writer == null) throw new DataOperationException("Repository is not initialized");
        logWriter.validate(log);
        boolean[] accepted = {false};
        writer.mutate(() -> {
            if (!reserve()) return;
            queue.add(log);
            accepted[0] = true;
        });
        if (!accepted[0]) writer.rejectLog(logWriter.logType(), log, capacity);
        return accepted[0];
    }
    private boolean reserve() {
        for (;;) {
            int current = queued.get();
            if (current >= capacity) return false;
            if (queued.compareAndSet(current, current + 1)) return true;
        }
    }
    /** The storage this repository writes to, whose failures its lane counts (D-WRITE-05). */
    final Object storage() { return logWriter; }
    /**
     * Returns how many logs were kept for the next flush after retryable failures. Takes no further batch once
     * the lane is stopping, so logs it never attempted stay where they were, in order.
     */
    final int drain(WriteBehindManager.Lane lane, int batchSize) {
        // Capture a finite boundary: continuous producers cannot starve state persistence.
        int remaining = queued.get(), kept = 0;
        List<E> retry = new ArrayList<>();
        while (remaining > 0 && !lane.stopping()) {
            List<E> batch = new ArrayList<>(Math.min(batchSize, remaining));
            while (batch.size() < batchSize && remaining-- > 0) {
                E log = queue.poll(); if (log == null) break;
                queued.decrementAndGet(); batch.add(log);
            }
            if (batch.isEmpty()) break;
            Map<String,List<E>> partitions = new LinkedHashMap<>();
            for (E log : batch) {
                try { partitions.computeIfAbsent(logWriter.table(log), ignored -> new ArrayList<>()).add(log); }
                catch (Throwable cause) {
                    lane.drop(logWriter.logType(), DataOperation.LOG_INSERT, log, new DataOperationException("Invalid log partition", cause), 1);
                }
            }
            for (var partition : partitions.entrySet())
                kept += lane.save(logWriter.logType(), DataOperation.LOG_INSERT, partition.getValue(),
                        values -> logWriter.insert(partition.getKey(), values), retry::add, log -> log);
        }
        // Retried on the next flush, after logs accepted meanwhile. They were accepted already, so they may
        // briefly exceed the capacity.
        queue.addAll(retry); queued.addAndGet(retry.size());
        return kept;
    }
    /** At close: reports every queued log as unsaved, without another attempt. */
    final void dropQueued(WriteBehindManager manager, Throwable cause) {
        for (E log; (log = queue.poll()) != null; ) {
            queued.decrementAndGet();
            manager.drop(logWriter.logType(), DataOperation.LOG_INSERT, log, cause, 0);
        }
    }
}
