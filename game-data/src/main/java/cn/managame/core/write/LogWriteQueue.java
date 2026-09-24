package cn.managame.core.write;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Per-table FIFO for logs, guarded by the table monitor. No identity map or operation merging. */
final class LogWriteQueue {
    private final ArrayDeque<WriteOperation<?>> queue = new ArrayDeque<>();

    int size() { return queue.size(); }
    void add(WriteOperation<?> operation) { queue.addLast(operation); }
    List<WriteOperation<?>> drain(int limit) {
        List<WriteOperation<?>> batch = new ArrayList<>(Math.min(limit, queue.size()));
        while (batch.size() < limit && !queue.isEmpty()) batch.add(queue.removeFirst());
        return batch;
    }
}