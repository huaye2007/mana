package cn.managame.runtime.execution;

import java.time.Duration;
import java.util.Objects;

final class ShutdownDeadline {
    private final long start = System.nanoTime();
    private final long budget;
    ShutdownDeadline(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative shutdown timeout");
        long nanos;
        try { nanos = timeout.toNanos(); } catch (ArithmeticException error) { nanos = Long.MAX_VALUE; }
        budget = nanos;
    }
    long remainingNanos() { return Math.max(0, budget - (System.nanoTime() - start)); }
    Duration remaining() { return Duration.ofNanos(remainingNanos()); }
}
