package cn.managame.metrics;

/**
 * Records durations, such as how long a task or a call took:
 *
 * <pre>{@code
 * long start = timer.start();
 * runTask();
 * timer.stop(start);
 * }</pre>
 *
 * An implementation gives {@link #record}; start and stop read {@link System#nanoTime()} around it, which the no-op
 * timer skips.
 */
@FunctionalInterface
public interface Timer {
    /** Records one duration of {@code nanos} nanoseconds. */
    void record(long nanos);

    /** The time to give {@link #stop}: {@link System#nanoTime()}, or 0 from the no-op timer, which reads no clock. */
    default long start() { return System.nanoTime(); }

    /** Records the time since {@code start}, a value {@link #start} returned. */
    default void stop(long start) { record(System.nanoTime() - start); }
}
