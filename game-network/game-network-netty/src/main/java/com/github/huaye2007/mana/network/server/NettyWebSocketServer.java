package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.connection.DefaultConnectionIdGenerator;
import com.github.huaye2007.mana.network.connection.IConnectionIdGenerator;
import com.github.huaye2007.mana.network.handler.INetworkHandler;
import com.github.huaye2007.mana.network.handler.ServerConnectionHandler;
import com.github.huaye2007.mana.network.handler.pipeline.DefaultWebSocketPipelineConfigurator;
import com.github.huaye2007.mana.network.handler.pipeline.IPipelineConfigurator;
import com.github.huaye2007.mana.network.session.SessionManager;

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
