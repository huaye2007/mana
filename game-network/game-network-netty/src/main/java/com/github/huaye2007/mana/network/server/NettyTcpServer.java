package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.connection.DefaultConnectionIdGenerator;
import com.github.huaye2007.mana.network.connection.IConnectionIdGenerator;
import com.github.huaye2007.mana.network.handler.INetworkHandler;
import com.github.huaye2007.mana.network.handler.ServerConnectionHandler;
import com.github.huaye2007.mana.network.handler.pipeline.CompositePipelineConfigurator;
import com.github.huaye2007.mana.network.handler.pipeline.DefaultTcpPipelineConfigurator;
import com.github.huaye2007.mana.network.handler.pipeline.IPipelineConfigurator;
import com.github.huaye2007.mana.network.session.SessionManager;

public class NettyTcpServer extends AbstractNettyServer {

    public NettyTcpServer(NetworkTcpServerConfig config, INetworkHandler networkHandler) {
        this(config, networkHandler, new SessionManager(), new ConnectionManager(),
                new DefaultConnectionIdGenerator());
    }

    public NettyTcpServer(NetworkTcpServerConfig config, INetworkHandler networkHandler,
                          IPipelineConfigurator beforeDispatcherConfigurator) {
        this(config, networkHandler, new SessionManager(), new ConnectionManager(),
                new DefaultConnectionIdGenerator(), beforeDispatcherConfigurator);
    }

    public NettyTcpServer(NetworkTcpServerConfig config, INetworkHandler networkHandler,
                          SessionManager sessionManager, ConnectionManager connectionManager,
                          IConnectionIdGenerator connectionIdGenerator) {
        this(config, networkHandler, sessionManager, connectionManager, connectionIdGenerator, null);
    }

    public NettyTcpServer(NetworkTcpServerConfig config, INetworkHandler networkHandler,
                          IConnectionIdGenerator connectionIdGenerator) {
        this(config, networkHandler, new SessionManager(), new ConnectionManager(), connectionIdGenerator, null);
    }

    public NettyTcpServer(NetworkTcpServerConfig config, INetworkHandler networkHandler,
                          IConnectionIdGenerator connectionIdGenerator,
                          IPipelineConfigurator beforeDispatcherConfigurator) {
        this(config, networkHandler, new SessionManager(), new ConnectionManager(),
                connectionIdGenerator, beforeDispatcherConfigurator);
    }

    public NettyTcpServer(NetworkTcpServerConfig config, INetworkHandler networkHandler,
                          SessionManager sessionManager, ConnectionManager connectionManager,
                          IConnectionIdGenerator connectionIdGenerator,
                          IPipelineConfigurator beforeDispatcherConfigurator) {
        super(config, buildPipelineConfigurator(networkHandler, sessionManager, connectionManager,
                connectionIdGenerator, beforeDispatcherConfigurator), sessionManager, connectionManager);
    }

    public NettyTcpServer(NetworkTcpServerConfig config, IPipelineConfigurator pipelineConfigurator) {
        this(config, pipelineConfigurator, new SessionManager(), new ConnectionManager());
    }

    public NettyTcpServer(NetworkTcpServerConfig config, IPipelineConfigurator pipelineConfigurator,
                          SessionManager sessionManager, ConnectionManager connectionManager) {
        super(config, pipelineConfigurator, sessionManager, connectionManager);
    }

    private static IPipelineConfigurator buildPipelineConfigurator(INetworkHandler networkHandler,
                                                                   SessionManager sessionManager,
                                                                   ConnectionManager connectionManager,
                                                                   IConnectionIdGenerator connectionIdGenerator,
                                                                   IPipelineConfigurator beforeDispatcherConfigurator) {
        ServerConnectionHandler connectionHandler = new ServerConnectionHandler(sessionManager, networkHandler);
        return CompositePipelineConfigurator.of(beforeDispatcherConfigurator,
                new DefaultTcpPipelineConfigurator(connectionHandler, connectionManager, connectionIdGenerator));
    }
}
