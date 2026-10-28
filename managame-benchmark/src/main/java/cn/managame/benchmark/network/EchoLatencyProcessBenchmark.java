package cn.managame.benchmark.network;

/**
 * {@link EchoLatencyBenchmark} with the echo server in a process of its own, as two server processes talk over
 * TCP: each side has its own JVM, garbage collection and threads, and its costs show apart.
 */
public class EchoLatencyProcessBenchmark extends EchoLatencyBenchmark {
    @Override protected boolean otherProcess() { return true; }
}
