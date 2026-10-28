package cn.managame.benchmark.rpc;

/**
 * {@link CallThroughputBenchmark} with the world server in a process of its own, as two server processes talk over TCP:
 * each side has its own JVM, garbage collection and threads, and its costs show apart.
 */
public class CallThroughputProcessBenchmark extends CallThroughputBenchmark {
    @Override protected boolean otherProcess() { return true; }
}
