package cn.managame.benchmark.network;

import cn.managame.benchmark.CountingMetrics;
import cn.managame.metrics.Metrics;

/** {@link EchoThroughputBenchmark} with the listener reporting to a backend, whose difference is what reporting costs a message. */
public class EchoThroughputMeteredBenchmark extends EchoThroughputBenchmark {
    @Override protected Metrics metrics() { return new CountingMetrics(); }
}
