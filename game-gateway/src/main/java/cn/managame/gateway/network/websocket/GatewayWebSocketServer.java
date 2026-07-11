package cn.managame.gateway.network.websocket;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.ServerConnectionIdGenerator;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.ServerConnectionHandler;
import cn.managame.network.server.NettyWebSocketServer;
import cn.managame.network.server.NetworkWsServerConfig;
import cn.managame.network.session.SessionManager;

import java.util.Objects;

public final class GatewayWebSocketServer {
    private final NettyWebSocketServer server;

    public GatewayWebSocketServer(int port, String websocketPath, int serverId,
                                  BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        this(port, websocketPath, new ServerConnectionIdGenerator(requireServerId(serverId)), bodyCodec, networkHandler);
    }

    public GatewayWebSocketServer(int port, String websocketPath, IConnectionIdGenerator connectionIdGenerator,
                                  BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        NetworkWsServerConfig config = new NetworkWsServerConfig(port);
        config.setWebsocketPath(websocketPath);
        SessionManager sessions = new SessionManager();
        ConnectionManager connections = new ConnectionManager();
        ServerConnectionHandler handler = new ServerConnectionHandler(sessions, Objects.requireNonNull(networkHandler, "networkHandler"));
        this.server = new NettyWebSocketServer(config,
                new GatewayWebSocketPipelineConfigurator(config, handler, connections,
                        Objects.requireNonNull(connectionIdGenerator, "connectionIdGenerator"), bodyCodec),
                sessions, connections);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(); }
    public NettyWebSocketServer unwrap() { return server; }

    private static int requireServerId(int serverId) {
        if (serverId < 0) throw new IllegalArgumentException("serverId must be non-negative");
        return serverId;
    }
}
