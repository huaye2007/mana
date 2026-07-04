package cn.managame.network.server;

import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.DefaultConnectionIdGenerator;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.INetworkHandler;
import cn.managame.network.handler.ServerConnectionHandler;
import cn.managame.network.handler.pipeline.DefaultWebSocketPipelineConfigurator;
import cn.managame.network.handler.pipeline.IPipelineConfigurator;
import cn.managame.network.session.SessionManager;

public class NettyWebSocketServer extends AbstractNettyServer {

    public NettyWebSocketServer(NetworkWsServerConfig config, INetworkHandler networkHandler) {
        this(config, networkHandler, new SessionManager(), new ConnectionManager(),
                new DefaultConnectionIdGenerator());
    }

    public NettyWebSocketServer(NetworkWsServerConfig config, INetworkHandler networkHandler,
                                SessionManager sessionManager, ConnectionManager connectionManager,
                                IConnectionIdGenerator connectionIdGenerator) {
        super(config, buildPipelineConfigurator(config, networkHandler, sessionManager,
                connectionManager, connectionIdGenerator), sessionManager, connectionManager);
    }

    public NettyWebSocketServer(NetworkWsServerConfig config, IPipelineConfigurator pipelineConfigurator) {
        this(config, pipelineConfigurator, new SessionManager(), new ConnectionManager());
    }

    public NettyWebSocketServer(NetworkWsServerConfig config, IPipelineConfigurator pipelineConfigurator,
                                SessionManager sessionManager, ConnectionManager connectionManager) {
        super(config, pipelineConfigurator, sessionManager, connectionManager);
    }

    private static IPipelineConfigurator buildPipelineConfigurator(NetworkWsServerConfig config,
                                                                   INetworkHandler networkHandler,
                                                                   SessionManager sessionManager,
                                                                   ConnectionManager connectionManager,
                                                                   IConnectionIdGenerator connectionIdGenerator) {
        ServerConnectionHandler connectionHandler = new ServerConnectionHandler(sessionManager, networkHandler);
        return new DefaultWebSocketPipelineConfigurator(config, connectionHandler,
                connectionManager, connectionIdGenerator);
    }
}
