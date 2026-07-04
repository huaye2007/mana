package cn.managame.network.handler.pipeline;

import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.IConnectionHandler;
import cn.managame.network.handler.WebsocketConnectionChannelHandler;
import cn.managame.network.handler.codec.PacketToWebSocketFrameEncoder;
import cn.managame.network.handler.codec.WebSocketFrameToPacketDecoder;
import cn.managame.network.server.NetworkWsServerConfig;
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
