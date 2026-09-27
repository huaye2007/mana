package cn.managame.network.netty;

import cn.managame.network.connector.WebSocketConnectOptions;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Binary WebSocket protocol assembly, establishment events, and rejection policy. */
final class WebSocketTransport implements ChannelTransport {
    static final String PROTOCOL = "managame-websocket";
    private static final int MAX_HTTP_CONTENT = 65536;
    private final Consumer<ChannelPipeline> protocol;
    private final int maxMessageSize;
    private final boolean server;

    private WebSocketTransport(Consumer<ChannelPipeline> protocol, int maxMessageSize, boolean server) {
        this.protocol = protocol;
        this.maxMessageSize = maxMessageSize;
        this.server = server;
    }

    static ChannelTransport server(String path, int maxMessageSize) {
        return new WebSocketTransport(pipeline -> {
            pipeline.addLast("managame-http", new HttpServerCodec());
            pipeline.addLast("managame-http-aggregate", new HttpObjectAggregator(MAX_HTTP_CONTENT));
            pipeline.addLast("managame-websocket-path", new WebSocketPathHandler(path));
            pipeline.addLast(PROTOCOL, new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                    .websocketPath(path).checkStartsWith(true).maxFramePayloadLength(maxMessageSize)
                    .allowExtensions(false).build()));
        }, maxMessageSize, true);
    }

    static ChannelTransport client(URI uri, WebSocketConnectOptions options, int maxMessageSize) {
        return new WebSocketTransport(pipeline -> {
            pipeline.addLast("managame-http", new HttpClientCodec());
            pipeline.addLast("managame-http-aggregate", new HttpObjectAggregator(MAX_HTTP_CONTENT));
            pipeline.addLast(PROTOCOL, new WebSocketClientProtocolHandler(WebSocketClientProtocolConfig.newBuilder()
                    .webSocketUri(uri).customHeaders(options.headers()).subprotocol(options.subprotocol())
                    .maxFramePayloadLength(maxMessageSize).allowExtensions(false).build()));
        }, maxMessageSize, false);
    }

    @Override public void addProtocolHandlers(ChannelPipeline pipeline, ConnectionLifecycle lifecycle) {
        var handshake = lifecycle.expectHandshake();
        protocol.accept(pipeline);
        pipeline.addLast("managame-websocket-aggregate", new WebSocketFrameAggregator(maxMessageSize));
        pipeline.addLast("managame-websocket-handshake", new HandshakeHandler(lifecycle, handshake, server));
    }

    @Override public void addPayloadHandlers(ChannelPipeline pipeline) {
        pipeline.addLast("managame-binary-in", new WebSocketBinaryFrameDecoder());
        pipeline.addLast("managame-binary-out", new WebSocketBinaryFrameEncoder());
    }

    private static final class HandshakeHandler extends ChannelInboundHandlerAdapter {
        private final ConnectionLifecycle lifecycle;
        private final ConnectionLifecycle.Handshake handshake;
        private final boolean server;
        private boolean complete;
        private ScheduledFuture<?> timeout;

        HandshakeHandler(ConnectionLifecycle lifecycle, ConnectionLifecycle.Handshake handshake, boolean server) {
            this.lifecycle = lifecycle;
            this.handshake = handshake;
            this.server = server;
        }

        @Override public void channelActive(ChannelHandlerContext ctx) {
            // Bound silent server-side peers that never send an HTTP Upgrade request.
            if (server && !complete) {
                timeout = ctx.executor().schedule(() -> {
                    if (!complete) lifecycle.fail(new WebSocketHandshakeException("WebSocket establishment timed out"));
                }, 10, TimeUnit.SECONDS);
            }
            ctx.fireChannelActive();
        }

        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                    || event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                complete = true;
                cancelTimeout();
                handshake.succeed();
            } else if (event instanceof WebSocketServerProtocolHandler.ServerHandshakeStateEvent
                    || event instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent) {
                if (event == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT
                        || event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
                    cancelTimeout();
                    lifecycle.fail(new WebSocketHandshakeException("WebSocket handshake timed out"));
                }
            } else ctx.fireUserEventTriggered(event);
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof TooLongFrameException || cause instanceof CorruptedWebSocketFrameException) {
                lifecycle.fail(cause);
                ctx.close();
            } else ctx.fireExceptionCaught(cause);
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) {
            cancelTimeout();
            ctx.fireChannelInactive();
        }

        @Override public void handlerRemoved(ChannelHandlerContext ctx) { cancelTimeout(); }

        private void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
                timeout = null;
            }
        }
    }
}
