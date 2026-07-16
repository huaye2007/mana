package cn.managame.gateway.network.tcp;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.network.server.INetworkServer;
import cn.managame.network.server.NettyServer;
import io.netty.channel.ChannelOption;

import java.util.Objects;

public final class GatewayTcpServer implements INetworkServer {
    private final NettyServer server;

    public GatewayTcpServer(int port, int readerIdleSeconds,
                            BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        GatewayTcpPipelineConfigurator pipeline = new GatewayTcpPipelineConfigurator(readerIdleSeconds, bodyCodec);
        this.server = NettyServer.tcp(Objects.requireNonNull(networkHandler, "networkHandler"))
                .bind(port)
                .bootstrap(bootstrap -> bootstrap
                        .option(ChannelOption.SO_BACKLOG, 1024)
                        .childOption(ChannelOption.TCP_NODELAY, true))
                .pipeline(pipeline::configure)
                .build();
    }

    @Override
    public void start() { server.start(); }
    @Override
    public void stop() { server.stop(); }
    public NettyServer unwrap() { return server; }
}
