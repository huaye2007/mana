package cn.managame.benchmark;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** A backend at its cheapest, counting in LongAdders as Micrometer does, for what reporting costs. */
public final class CountingMetrics implements Metrics {
    @Override public Counter counter(String name, String... tags) { return new LongAdder()::add; }

    @Override public Meter counter(String name, LongSupplier count, String... tags) { return () -> {}; }

    @Override public Meter gauge(String name, DoubleSupplier value, String... tags) { return () -> {}; }
}
