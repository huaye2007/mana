package com.github.huaye2007.mana.network.handler.pipeline;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.connection.IConnectionIdGenerator;
import com.github.huaye2007.mana.network.handler.IConnectionHandler;
import com.github.huaye2007.mana.network.handler.WebsocketConnectionChannelHandler;
import com.github.huaye2007.mana.network.handler.codec.PacketToWebSocketFrameEncoder;
import com.github.huaye2007.mana.network.handler.codec.WebSocketFrameToPacketDecoder;
import com.github.huaye2007.mana.network.server.NetworkWsServerConfig;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

public class DefaultWebSocketPipelineConfigurator implements IPipelineConfigurator {

    private final NetworkWsServerConfig config;
    private final IConnectionHandler connectionHandler;
    private final ConnectionManager connectionManager;
    private final IConnectionIdGenerator connectionIdGenerator;

    public DefaultWebSocketPipelineConfigurator(NetworkWsServerConfig config, IConnectionHandler connectionHandler,
                                                ConnectionManager connectionManager,
                                                IConnectionIdGenerator connectionIdGenerator) {
        this.config = config;
        this.connectionHandler = connectionHandler;
        this.connectionManager = connectionManager;
        this.connectionIdGenerator = connectionIdGenerator;
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast(PipelineConstants.NAME_HTTP_CODEC, new HttpServerCodec());
        pipeline.addLast(PipelineConstants.NAME_HTTP_AGGREGATOR,
                new HttpObjectAggregator(config.getHttpMaxContentLength()));
        pipeline.addLast(PipelineConstants.NAME_WEBSOCKET_PROTOCOL,
                new WebSocketServerProtocolHandler(config.getWebsocketPath(), null, true));
        configureBeforeDispatcher(pipeline);
        pipeline.addLast(PipelineConstants.NAME_WEBSOCKET_FRAME_DECODER, new WebSocketFrameToPacketDecoder());
        pipeline.addLast(PipelineConstants.NAME_WEBSOCKET_FRAME_ENCODER, new PacketToWebSocketFrameEncoder());
        pipeline.addLast(PipelineConstants.NAME_DISPATCHER,
                new WebsocketConnectionChannelHandler(connectionHandler, connectionManager, connectionIdGenerator));
    }

    protected void configureBeforeDispatcher(ChannelPipeline pipeline) {
    }

    protected NetworkWsServerConfig getConfig() {
        return config;
    }
}
