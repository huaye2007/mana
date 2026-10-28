package cn.managame.benchmark.rpc;

/**
 * {@link NotifyThroughputBenchmark} with the world server in a process of its own, as two server processes talk over TCP:
 * each side has its own JVM, garbage collection and threads, and its costs show apart.
 */
public class NotifyThroughputProcessBenchmark extends NotifyThroughputBenchmark {
    @Override protected boolean otherProcess() { return true; }
}
