package cn.managame.metrics;

/** Counts events, such as tasks refused or calls timed out; a backend derives their rate. */
@FunctionalInterface
public interface Counter {
    /** Counts one event. */
    default void increment() { increment(1); }

    /** Counts {@code amount} events at once; never negative. */
    void increment(long amount);
}
