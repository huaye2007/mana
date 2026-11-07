package cn.managame.benchmark.common;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Metrics;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What a component pays on its hot path to report metrics. With the default {@link Metrics#noop()}: counting an event,
 * against doing nothing at all. With a backend bound, here one counting in a LongAdder as Micrometer does: a counter
 * through Metrics against the LongAdder used directly, by one thread and by four sharing it, which is what Metrics adds
 * over the backend's own cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class MetricsBenchmark {
    private final Counter noopCounter = Metrics.noop().counter("managame.benchmark.events", "domain", "1");
    private final LongAdder adder = new LongAdder();
    private final Counter counter = adder::add;

    /** Nothing, the floor the others compare with. */
    @Benchmark public void nothing() {}

    @Benchmark public void noopCounter() { noopCounter.increment(); }


    /** The backend's own cost, which {@code counter} compares with. */
    @Benchmark public void adder() { adder.increment(); }

    @Benchmark public void counter() { counter.increment(); }

    @Benchmark @Threads(4) public void adderShared() { adder.increment(); }

    @Benchmark @Threads(4) public void counterShared() { counter.increment(); }
}
