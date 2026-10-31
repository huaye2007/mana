package cn.managame.data;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** One log type's logs not yet written, at most {@code capacity} of them, and the writer that writes them. */
final class LogQueue {
    final Class<?> type;
    final LogWriter writer;
    final int capacity;
    private final ConcurrentLinkedQueue<Object> logs = new ConcurrentLinkedQueue<>();
    /** The number of queued logs, which ConcurrentLinkedQueue would count by walking it. */
    private final AtomicInteger size = new AtomicInteger();
    /** Adds in progress in the low bits, and the sign bit once closed, so close can wait for the adds already in. */
    private final AtomicLong addsInProgress = new AtomicLong();

    LogQueue(Class<?> type, LogWriter writer, int capacity) {
        this.type = type;
        this.writer = writer;
        this.capacity = capacity;
    }

    /** Queues a log; false when the queue is full. IllegalStateException once closed. */
    boolean add(Object log) {
        if (addsInProgress.getAndIncrement() < 0) {
            addsInProgress.decrementAndGet();
            throw new IllegalStateException("GameData is closed");
        }
        try {
            for (int current = size.get(); current < capacity; current = size.get()) {
                if (size.compareAndSet(current, current + 1)) {
                    logs.add(log);
                    return true;
                }
            }
            return false;
        } finally {
            addsInProgress.decrementAndGet();
        }
    }

    boolean isEmpty() { return size.get() == 0; }

    int size() { return size.get(); }

    /** The oldest queued log, or null. */
    Object poll() {
        Object log = logs.poll();
        if (log != null) size.decrementAndGet();
        return log;
    }

    /** Queues logs a round could not write again, behind those queued meanwhile; they may pass the capacity. */
    void putBack(List<Object> unwritten) {
        logs.addAll(unwritten);
        size.addAndGet(unwritten.size());
    }

    /** Refuses every later log, and returns once the adds already in progress have finished. */
    void close() {
        addsInProgress.getAndUpdate(value -> value | Long.MIN_VALUE);
        while ((addsInProgress.get() & Long.MAX_VALUE) != 0) LockSupport.parkNanos(10_000);
    }
}
