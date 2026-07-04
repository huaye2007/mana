package cn.managame.network.server;

import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.DefaultConnectionIdGenerator;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.IConnectionHandler;
import cn.managame.network.handler.pipeline.CompositePipelineConfigurator;
import cn.managame.network.handler.pipeline.DefaultTcpPipelineConfigurator;
import cn.managame.network.handler.pipeline.IPipelineConfigurator;

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
