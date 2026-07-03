package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.connection.DefaultConnectionIdGenerator;
import com.github.huaye2007.mana.network.connection.IConnectionIdGenerator;
import com.github.huaye2007.mana.network.handler.IConnectionHandler;
import com.github.huaye2007.mana.network.handler.pipeline.CompositePipelineConfigurator;
import com.github.huaye2007.mana.network.handler.pipeline.DefaultTcpPipelineConfigurator;
import com.github.huaye2007.mana.network.handler.pipeline.IPipelineConfigurator;

public class NettyInternalTcpServer extends AbstractNettyServer {

    public NettyInternalTcpServer(NetworkTcpServerConfig config, IConnectionHandler connectionHandler) {
        this(config, connectionHandler, new ConnectionManager(), new DefaultConnectionIdGenerator());
    }

    public NettyInternalTcpServer(NetworkTcpServerConfig config, IConnectionHandler connectionHandler,
                                  IPipelineConfigurator beforeDispatcherConfigurator) {
        this(config, connectionHandler, new ConnectionManager(), new DefaultConnectionIdGenerator(),
                beforeDispatcherConfigurator);
    }

    public NettyInternalTcpServer(NetworkTcpServerConfig config, IConnectionHandler connectionHandler,
                                  ConnectionManager connectionManager,
                                  IConnectionIdGenerator connectionIdGenerator) {
        this(config, connectionHandler, connectionManager, connectionIdGenerator, null);
    }

    public NettyInternalTcpServer(NetworkTcpServerConfig config, IConnectionHandler connectionHandler,
                                  ConnectionManager connectionManager,
                                  IConnectionIdGenerator connectionIdGenerator,
                                  IPipelineConfigurator beforeDispatcherConfigurator) {
        super(config, buildPipelineConfigurator(connectionHandler, connectionManager,
                connectionIdGenerator, beforeDispatcherConfigurator), null, connectionManager);
    }

    public NettyInternalTcpServer(NetworkTcpServerConfig config, IPipelineConfigurator pipelineConfigurator) {
        this(config, pipelineConfigurator, new ConnectionManager());
    }

    public NettyInternalTcpServer(NetworkTcpServerConfig config, IPipelineConfigurator pipelineConfigurator,
                                  ConnectionManager connectionManager) {
        super(config, pipelineConfigurator, null, connectionManager);
    }

    private static IPipelineConfigurator buildPipelineConfigurator(IConnectionHandler connectionHandler,
                                                                   ConnectionManager connectionManager,
                                                                   IConnectionIdGenerator connectionIdGenerator,
                                                                   IPipelineConfigurator beforeDispatcherConfigurator) {
        return CompositePipelineConfigurator.of(beforeDispatcherConfigurator,
                new DefaultTcpPipelineConfigurator(connectionHandler, connectionManager, connectionIdGenerator));
    }
}
