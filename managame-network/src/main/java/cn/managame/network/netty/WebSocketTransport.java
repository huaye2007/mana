package cn.managame.network.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.util.ReferenceCountUtil;

/** Server-side WebSocket section of a pipeline, built from one WebSocketConfig. */
final class WebSocketTransport {
    private static final int MAX_HANDSHAKE_CONTENT = 65536;
    private final WebSocketConfig config;
    private final WebSocketServerProtocolConfig protocol;

    WebSocketTransport(WebSocketConfig config, long handshakeTimeoutMillis) {
        this.config = config;
        protocol = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(config.path())
                .checkStartsWith(true) // PathHandler enforces the exact path and lets a query string through
                .subprotocols(config.subprotocols().isEmpty() ? null : String.join(",", config.subprotocols()))
                .maxFramePayloadLength(config.maxMessageSize())
                .allowExtensions(config.compression())
                .handshakeTimeoutMillis(handshakeTimeoutMillis)
                .build();
    }

    WebSocketConfig config() { return config; }

    void install(ChannelPipeline pipeline) {
        pipeline.addLast(PipelineNames.HTTP_CODEC, new HttpServerCodec());
        pipeline.addLast(PipelineNames.HTTP_AGGREGATOR, new HttpObjectAggregator(MAX_HANDSHAKE_CONTENT));
        pipeline.addLast(PipelineNames.WEBSOCKET_PATH, new PathHandler(config.path()));
        if (config.compression())
            pipeline.addLast(PipelineNames.WEBSOCKET_COMPRESSION, new WebSocketServerCompressionHandler(config.maxMessageSize()));
        pipeline.addLast(PipelineNames.WEBSOCKET_PROTOCOL, new WebSocketServerProtocolHandler(protocol));
        pipeline.addLast(PipelineNames.WEBSOCKET_AGGREGATOR, new FrameAggregator(config.maxMessageSize()));
        switch (config.messages()) {
            case BINARY -> pipeline.addLast(PipelineNames.WEBSOCKET_MESSAGES, MessageCodec.BINARY);
            case TEXT -> pipeline.addLast(PipelineNames.WEBSOCKET_MESSAGES, MessageCodec.TEXT);
            case FRAMES -> {}
        }
    }

    /** Answers any path but the configured one with 404; leaves the pipeline once the path matched. */
    private static final class PathHandler extends ChannelInboundHandlerAdapter {
        private final String path;

        PathHandler(String path) { this.path = path; }

        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof HttpRequest request) {
                String uri = request.uri();
                int query = uri.indexOf('?');
                if (!path.equals(query < 0 ? uri : uri.substring(0, query))) {
                    ReferenceCountUtil.release(message);
                    DefaultFullHttpResponse response =
                            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                ctx.pipeline().remove(this);
            }
            ctx.fireChannelRead(message);
        }
    }

    /** Closes with 1009 instead of reporting an exception and continuing, as Netty's default does. */
    private static final class FrameAggregator extends WebSocketFrameAggregator {
        FrameAggregator(int maxMessageSize) { super(maxMessageSize); }

        @Override protected void handleOversizedMessage(ChannelHandlerContext ctx, WebSocketFrame oversized) {
            ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.MESSAGE_TOO_BIG))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    /** Data frames of the configured type in, ByteBuf payloads out; other frame types pass through. */
    @Sharable
    private static final class MessageCodec extends ChannelDuplexHandler {
        static final MessageCodec BINARY = new MessageCodec(false), TEXT = new MessageCodec(true);
        private final boolean text;

        private MessageCodec(boolean text) { this.text = text; }

        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof BinaryWebSocketFrame || message instanceof TextWebSocketFrame) {
                WebSocketFrame frame = (WebSocketFrame) message;
                if ((frame instanceof TextWebSocketFrame) == text) {
                    ctx.fireChannelRead(frame.content()); // the payload carries the frame's reference
                } else {
                    frame.release();
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_MESSAGE_TYPE))
                            .addListener(ChannelFutureListener.CLOSE);
                }
                return;
            }
            ctx.fireChannelRead(message);
        }

        @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            if (message instanceof ByteBuf payload)
                message = text ? new TextWebSocketFrame(payload) : new BinaryWebSocketFrame(payload);
            ctx.write(message, promise);
        }
    }
}
