package cn.managame.gateway.network.tcp;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.network.connection.ServerConnectionIdGenerator;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.server.NettyTcpServer;
import cn.managame.network.server.NetworkTcpServerConfig;

import java.util.Objects;

public final class GatewayTcpServer {
    private final NettyTcpServer server;

    public GatewayTcpServer(int port, int serverId, int readerIdleSeconds,
                            BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        this(port, new ServerConnectionIdGenerator(requireServerId(serverId)), readerIdleSeconds, bodyCodec, networkHandler);
    }

    public GatewayTcpServer(int port, IConnectionIdGenerator connectionIdGenerator, int readerIdleSeconds,
                            BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        NetworkTcpServerConfig config = new NetworkTcpServerConfig(port);
        this.server = new NettyTcpServer(config, Objects.requireNonNull(networkHandler, "networkHandler"),
                Objects.requireNonNull(connectionIdGenerator, "connectionIdGenerator"),
                new GatewayTcpPipelineConfigurator(readerIdleSeconds, bodyCodec));
    }

    public void start() { server.start(); }
    public void stop() { server.stop(); }
    public NettyTcpServer unwrap() { return server; }

    private static int requireServerId(int serverId) {
        if (serverId < 0) throw new IllegalArgumentException("serverId must be non-negative");
        return serverId;
    }
}
