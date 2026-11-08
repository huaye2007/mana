package cn.managame.benchmark.rpc;

import cn.managame.benchmark.CountingMetrics;
import cn.managame.metrics.Metrics;

/** {@link CallThroughputBenchmark} with both nodes reporting to a backend, whose difference is what reporting costs a call. */
public class CallThroughputMeteredBenchmark extends CallThroughputBenchmark {
    @Override protected Metrics metrics() { return new CountingMetrics(); }
}
