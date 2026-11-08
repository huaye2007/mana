package cn.managame.benchmark.runtime;

import cn.managame.benchmark.CountingMetrics;
import cn.managame.metrics.Metrics;

/** {@link DispatchThroughputBenchmark} with the runtime reporting to a backend, whose difference is what reporting costs a request. */
public class DispatchThroughputMeteredBenchmark extends DispatchThroughputBenchmark {
    @Override protected Metrics metrics() { return new CountingMetrics(); }
}
