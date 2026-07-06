package cn.managame.gateway.network.websocket;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.connection.ServerConnectionIdGenerator;
import cn.managame.network.handler.ServerConnectionHandler;
import cn.managame.network.server.NettyWebSocketServer;
import cn.managame.network.server.NetworkWsServerConfig;
import cn.managame.network.session.SessionManager;

/**
 * 网关外网 WebSocket 服务端：浏览器/H5 客户端走这里，帧体与 TCP 同格式。
 * 自行装配派发器（{@link ServerConnectionHandler} 复用 {@link GatewayNetworkHandler}）
 * 与 {@link GatewayWebSocketPipelineConfigurator}，共用同一个 {@link GatewayNetworkHandler}
 * 和会话表，后端推送时不关心客户端接入方式。
 */
public class GatewayWebSocketServer {

    private final NettyWebSocketServer server;

    public GatewayWebSocketServer(int port, String websocketPath, int serverId,
                                  BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        NetworkWsServerConfig config = new NetworkWsServerConfig(port);
        config.setWebsocketPath(websocketPath);

        SessionManager sessionManager = new SessionManager();
        ConnectionManager connectionManager = new ConnectionManager();
        IConnectionIdGenerator idGenerator = new ServerConnectionIdGenerator(serverId);
        ServerConnectionHandler connectionHandler = new ServerConnectionHandler(sessionManager, networkHandler);

        GatewayWebSocketPipelineConfigurator configurator = new GatewayWebSocketPipelineConfigurator(
                config, connectionHandler, connectionManager, idGenerator, bodyCodec);
        this.server = new NettyWebSocketServer(config, configurator, sessionManager, connectionManager);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop();
    }
}
