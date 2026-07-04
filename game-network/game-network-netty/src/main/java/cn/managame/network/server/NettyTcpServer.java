package cn.managame.network.server;

import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.DefaultConnectionIdGenerator;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.INetworkHandler;
import cn.managame.network.handler.ServerConnectionHandler;
import cn.managame.network.handler.pipeline.CompositePipelineConfigurator;
import cn.managame.network.handler.pipeline.DefaultTcpPipelineConfigurator;
import cn.managame.network.handler.pipeline.IPipelineConfigurator;
import cn.managame.network.session.SessionManager;

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
