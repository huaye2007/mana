package cn.managame.benchmark.network;

import cn.managame.benchmark.PeerProcess;
import cn.managame.network.netty.NetworkServer;

/** The echo server of {@link EchoFixture} in a process of its own, for the benchmarks that run their two sides apart. */
public final class EchoPeer {
    private EchoPeer() {}

    public static void main(String[] args) throws Exception {
        NetworkServer server = EchoFixture.startServer();
        PeerProcess.serve(server::close, server.port());
        System.exit(0);
    }
}
