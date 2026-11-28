package cn.managame.benchmark.ai;

import cn.managame.benchmark.PeerProcess;

/** Runs the HTTP echo, SSE, and {@link cn.managame.ai.mcp.McpHttpHandler} server in a separate process. */
public final class AiHttpPeer {
    private AiHttpPeer() {}

    public static void main(String[] args) throws Exception {
        AiHttpBenchmark.ServerFixture fixture = AiHttpBenchmark.startServer();
        PeerProcess.serve(fixture, fixture.port());
    }
}
