package cn.managame.data.internal;

import cn.managame.data.spi.LogWriter;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One log type's logs not yet written, at most {@code capacity} of them, and the writer that writes them. GameData is
 * closed once the business has stopped inserting, so closing tracks no insert in flight. Not API.
 */
public final class LogQueue {
    final Class<?> type;
    final LogWriter writer;
    final int capacity;
    private final ConcurrentLinkedQueue<Object> logs = new ConcurrentLinkedQueue<>();
    /** Logs queued, for the capacity: ConcurrentLinkedQueue would count them by walking it. */
    private final AtomicInteger size = new AtomicInteger();
    private volatile boolean closed;
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
        if (closed) throw new IllegalStateException("GameData is closed");
        if (size.getAndIncrement() >= capacity) {   // one atomic add, which unlike a compare-and-set never retries
            size.decrementAndGet();
            refused.incrementAndGet();
            return false;
        }
        logs.add(log);
        return true;
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

    /** Refuses every later log. */
    void close() { closed = true; }
}
