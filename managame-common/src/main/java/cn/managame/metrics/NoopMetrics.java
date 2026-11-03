package cn.managame.metrics;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** {@link Metrics#noop()}: one instance is every meter, each method empty. */
final class NoopMetrics implements Metrics, Counter, Timer, Meter {
    static final NoopMetrics INSTANCE = new NoopMetrics();

    private NoopMetrics() {}

    @Override public Counter counter(String name, String... tags) { return this; }
    @Override public Meter counter(String name, LongSupplier count, String... tags) { return this; }
    @Override public Meter gauge(String name, DoubleSupplier value, String... tags) { return this; }
    @Override public Timer timer(String name, String... tags) { return this; }

    @Override public void increment() {}
    @Override public void increment(long amount) {}
    @Override public void record(long nanos) {}
    @Override public long start() { return 0; }
    @Override public void stop(long start) {}
    @Override public void close() {}

    @Override public String toString() { return "Metrics.noop()"; }
}
