package cn.managame.gateway.network.websocket;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.codec.GatewayPacketDecoder;
import cn.managame.gateway.codec.GatewayPacketEncoder;
import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.IConnectionHandler;
import cn.managame.network.handler.WebsocketConnectionChannelHandler;
import cn.managame.network.handler.codec.PacketToWebSocketFrameEncoder;
import cn.managame.network.handler.codec.WebSocketFrameToPacketDecoder;
import cn.managame.network.handler.pipeline.IPipelineConfigurator;
import cn.managame.network.server.NetworkWsServerConfig;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.Objects;

public final class GatewayWebSocketPipelineConfigurator implements IPipelineConfigurator {
    private final NetworkWsServerConfig config;
    private final IConnectionHandler connectionHandler;
    private final ConnectionManager connectionManager;
    private final IConnectionIdGenerator connectionIdGenerator;
    private final BodyCodec bodyCodec;

    public GatewayWebSocketPipelineConfigurator(NetworkWsServerConfig config, IConnectionHandler connectionHandler,
                                                ConnectionManager connectionManager,
                                                IConnectionIdGenerator connectionIdGenerator, BodyCodec bodyCodec) {
        this.config = Objects.requireNonNull(config, "config");
        this.connectionHandler = Objects.requireNonNull(connectionHandler, "connectionHandler");
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.connectionIdGenerator = Objects.requireNonNull(connectionIdGenerator, "connectionIdGenerator");
        this.bodyCodec = Objects.requireNonNull(bodyCodec, "bodyCodec");
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast("httpCodec", new HttpServerCodec());
        pipeline.addLast("httpAggregator", new HttpObjectAggregator(config.getHttpMaxContentLength()));
        pipeline.addLast("webSocketProtocol", new WebSocketServerProtocolHandler(config.getWebsocketPath(), null, true));
        pipeline.addLast("webSocketFrameDecoder", new WebSocketFrameToPacketDecoder());
        pipeline.addLast("gatewayPacketDecoder", new GatewayPacketDecoder(bodyCodec));
        // Outbound handlers run in reverse order: gateway packet -> ByteBuf -> binary WebSocket frame.
        pipeline.addLast("webSocketFrameEncoder", new PacketToWebSocketFrameEncoder());
        pipeline.addLast("gatewayPacketEncoder", new GatewayPacketEncoder(bodyCodec));
        pipeline.addLast("gatewayDispatcher", new WebsocketConnectionChannelHandler(
                connectionHandler, connectionManager, connectionIdGenerator));
    }
}
