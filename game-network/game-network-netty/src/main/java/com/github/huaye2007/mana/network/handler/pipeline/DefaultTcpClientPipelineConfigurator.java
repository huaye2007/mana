package com.github.huaye2007.mana.network.handler.pipeline;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.connection.IConnectionIdGenerator;
import com.github.huaye2007.mana.network.handler.IConnectionHandler;
import com.github.huaye2007.mana.network.handler.NettyConnectionChannelHandler;
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
