package cn.managame.gateway.network.websocket;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.gateway.network.tcp.GatewayTcpPipelineConfigurator;
import cn.managame.network.server.INetworkServer;
import cn.managame.network.server.NettyServer;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;

import java.util.Objects;

public final class GatewayWebSocketServer implements INetworkServer {
    private final NettyServer server;

    public GatewayWebSocketServer(int port, String websocketPath,
                                  BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        GatewayTcpPipelineConfigurator pipeline = new GatewayTcpPipelineConfigurator(0, bodyCodec);
        WebSocketServerProtocolConfig protocol = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(normalizePath(websocketPath))
                .allowExtensions(true)
                .build();
        this.server = NettyServer.webSocket(protocol, Objects.requireNonNull(networkHandler, "networkHandler"))
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

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }
}
