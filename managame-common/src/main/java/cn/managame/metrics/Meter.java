package cn.managame.metrics;

/** A gauge, or a counter kept elsewhere: a meter the backend reads when it collects, holding what it reads. */
@FunctionalInterface
public interface Meter {
    /**
     * Removes the meter from the backend and lets go of what it reads, for a meter of something that goes away, such
     * as a peer; idempotent.
     */
    void close();
}
