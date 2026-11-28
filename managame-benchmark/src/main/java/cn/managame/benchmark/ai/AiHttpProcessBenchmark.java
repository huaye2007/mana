package cn.managame.benchmark.ai;

/**
 * {@link AiHttpBenchmark} with the AI and MCP HTTP server running in a separate {@link cn.managame.benchmark.PeerProcess}
 * so client and server CPU, allocation, GC, and direct memory costs are measured apart.
 */
public class AiHttpProcessBenchmark extends AiHttpBenchmark {
    @Override protected boolean otherProcess() { return true; }
}
