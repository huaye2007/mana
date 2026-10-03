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
    final void drain(WriteBehindManager manager, int batchSize) {
        // Capture a finite boundary: continuous producers cannot starve state persistence.
        int remaining = queue.size();
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
                    manager.execute(logWriter.logType(), DataOperation.LOG_INSERT, List.of(log), ignored -> { throw new DataOperationException("Invalid log partition", cause); });
                }
            }
            partitions.forEach((table, logs) -> manager.execute(logWriter.logType(), DataOperation.LOG_INSERT, logs,
                    values -> logWriter.insert(table, values)));
        }
    }
}
