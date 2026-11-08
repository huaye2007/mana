package cn.managame.benchmark.runtime;

import cn.managame.benchmark.CountingMetrics;
import cn.managame.metrics.Metrics;

/** {@link ExecuteThroughputBenchmark} with the runtime reporting to a backend, whose difference is what reporting costs a task. */
public class ExecuteThroughputMeteredBenchmark extends ExecuteThroughputBenchmark {
    @Override protected Metrics metrics() { return new CountingMetrics(); }
}
