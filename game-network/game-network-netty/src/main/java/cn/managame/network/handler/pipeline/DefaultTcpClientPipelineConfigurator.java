package cn.managame.network.handler.pipeline;

import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.IConnectionHandler;
import cn.managame.network.handler.NettyConnectionChannelHandler;
import io.netty.channel.ChannelPipeline;

public class DefaultTcpClientPipelineConfigurator implements IPipelineConfigurator {

    private final IConnectionHandler connectionHandler;
    private final ConnectionManager connectionManager;
    private final IConnectionIdGenerator connectionIdGenerator;

    public DefaultTcpClientPipelineConfigurator(IConnectionHandler connectionHandler,
                                                ConnectionManager connectionManager,
                                                IConnectionIdGenerator connectionIdGenerator) {
        this.connectionHandler = connectionHandler;
        this.connectionManager = connectionManager;
        this.connectionIdGenerator = connectionIdGenerator;
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast(PipelineConstants.NAME_DISPATCHER,
                new NettyConnectionChannelHandler(connectionHandler, connectionManager, connectionIdGenerator));
    }
}
