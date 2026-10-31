package cn.managame.data.internal;

import cn.managame.data.spi.LogWriter;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** One log type's logs not yet written, at most {@code capacity} of them, and the writer that writes them. Not API. */
public final class LogQueue {
    final Class<?> type;
    final LogWriter writer;
    final int capacity;
    private final ConcurrentLinkedQueue<Object> logs = new ConcurrentLinkedQueue<>();
    /** The number of queued logs, which ConcurrentLinkedQueue would count by walking it. */
    private final AtomicInteger size = new AtomicInteger();
    /** Adds in progress in the low bits, and the sign bit once closed, so close can wait for the adds already in. */
    private final AtomicLong addsInProgress = new AtomicLong();
    /** What the rounds did since GameData was built, counted by the write thread; read by stats. */
    final AtomicLong written = new AtomicLong(), failures = new AtomicLong(), dropped = new AtomicLong();
    /** Logs refused because the queue was full. */
    private final AtomicLong refused = new AtomicLong();

    public LogQueue(Class<?> type, LogWriter writer, int capacity) {
        this.type = type;
        this.writer = writer;
        this.capacity = capacity;
    }

    public Class<?> type() { return type; }

    public int capacity() { return capacity; }

    /** Logs waiting in the queue; those the round in progress took are not among them. */
    public int queued() { return size.get(); }

    /** Logs written since GameData was built. */
    public long written() { return written.get(); }

    /** Logs refused since GameData was built because the queue was full. */
    public long refused() { return refused.get(); }

    /** Writes that failed since GameData was built, a batch or a single log each counting once. */
    public long failures() { return failures.get(); }

    /** Logs a round handed to the DataErrorHandler since GameData was built; refused ones are not among them. */
    public long dropped() { return dropped.get(); }

    /** Queues a log; false, counted as refused, when the queue is full. IllegalStateException once closed. */
    public boolean add(Object log) {
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
            refused.incrementAndGet();
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
