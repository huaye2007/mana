package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.mysql.MysqlLogWriter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class LogRepository<E> {
    private final ConcurrentLinkedQueue<E> queue = new ConcurrentLinkedQueue<>();
    private MysqlLogWriter logWriter;
    private WriteBehindManager writer;
    protected LogRepository() {}
    final long queuedLogs() { return queue.size(); }
    final void initialize(MysqlLogWriter logWriter, WriteBehindManager writer) {
        this.logWriter = logWriter; this.writer = writer;
    }
    /** The accepted log and its partition fields must not be modified afterwards. */
    public final void insert(E log) {
        Objects.requireNonNull(log);
        if (writer == null) throw new DataOperationException("Repository is not initialized");
        logWriter.validate(log);
        writer.mutate(() -> queue.add(log));
    }
    /** Returns how many logs were kept for the next flush after retryable failures. */
    final int drain(WriteBehindManager manager, int batchSize) {
        // Capture a finite boundary: continuous producers cannot starve state persistence.
        int remaining = queue.size(), kept = 0;
        List<E> retry = new ArrayList<>();
        while (remaining > 0) {
            List<E> batch = new ArrayList<>(Math.min(batchSize, remaining));
            while (batch.size() < batchSize && remaining-- > 0) {
                E log = queue.poll(); if (log == null) break; batch.add(log);
            }
            if (batch.isEmpty()) break;
            Map<String,List<E>> partitions = new LinkedHashMap<>();
            for (E log : batch) {
                try { partitions.computeIfAbsent(logWriter.table(log), ignored -> new ArrayList<>()).add(log); }
                catch (Throwable cause) {
                    manager.drop(logWriter.logType(), DataOperation.LOG_INSERT, log, new DataOperationException("Invalid log partition", cause), 1);
                }
            }
            for (var partition : partitions.entrySet())
                kept += manager.save(logWriter.logType(), DataOperation.LOG_INSERT, partition.getValue(),
                        values -> logWriter.insert(partition.getKey(), values), saved -> {}, retry::add, log -> log);
        }
        queue.addAll(retry); // retried on the next flush, after logs accepted meanwhile
        return kept;
    }
}
