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
final class WebSocketTransport {
    static final String PROTOCOL = "network-websocket";
    private static final int MAX_HTTP_CONTENT = 65536;
    private final Consumer<ChannelPipeline> protocol;
    private final int maxMessageSize;
    private final long handshakeTimeoutMillis;
    private final boolean server, secure;

    private WebSocketTransport(Consumer<ChannelPipeline> protocol, int maxMessageSize, boolean server, boolean secure, long handshakeTimeoutMillis) {
        this.protocol = protocol;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.maxMessageSize = maxMessageSize;
        this.server = server;
        this.secure = secure;
    }

    static WebSocketTransport server(String path, int maxMessageSize) {
        WebSocketServerProtocolConfig config = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(path).checkStartsWith(true).maxFramePayloadLength(maxMessageSize)
                .allowExtensions(false).build();
        return new WebSocketTransport(pipeline -> {
            pipeline.addLast("network-http", new HttpServerCodec());
            pipeline.addLast("network-http-aggregate", new HttpObjectAggregator(MAX_HTTP_CONTENT));
            pipeline.addLast("network-websocket-path", new WebSocketPathHandler(path));
            pipeline.addLast(PROTOCOL, new WebSocketServerProtocolHandler(config));
        }, maxMessageSize, true, false, config.handshakeTimeoutMillis());
    }

    static WebSocketTransport client(URI uri, WebSocketConnectOptions options, int maxMessageSize) {
        WebSocketClientProtocolConfig config = WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(uri).customHeaders(options.headers()).subprotocol(options.subprotocol())
                .maxFramePayloadLength(maxMessageSize).allowExtensions(false).build();
        return new WebSocketTransport(pipeline -> {
            pipeline.addLast("network-http", new HttpClientCodec());
            pipeline.addLast("network-http-aggregate", new HttpObjectAggregator(MAX_HTTP_CONTENT));
            pipeline.addLast(PROTOCOL, new WebSocketClientProtocolHandler(config));
        }, maxMessageSize, false, "wss".equalsIgnoreCase(uri.getScheme()), config.handshakeTimeoutMillis());
    }

    void addProtocolHandlers(ChannelPipeline pipeline) {
        protocol.accept(pipeline);
        pipeline.addLast("network-websocket-aggregate", new WebSocketFrameAggregator(maxMessageSize));
        pipeline.addLast("network-websocket-handshake", new HandshakeHandler(server, handshakeTimeoutMillis));
    }

    void addPayloadHandlers(ChannelPipeline pipeline) {
        pipeline.addLast("network-binary-in", new WebSocketBinaryFrameDecoder());
        pipeline.addLast("network-binary-out", new WebSocketBinaryFrameEncoder());
    }

    void verifyScheme(boolean tls) {
        if (!server && secure != tls)
            throw new IllegalArgumentException(secure
                    ? "wss URI requires an SslHandler at the start of the pipeline"
                    : "ws URI cannot use an SslHandler; use wss");
    }

    private static final class HandshakeHandler extends ChannelInboundHandlerAdapter {
        private boolean complete;
        private final boolean server;
        private final long handshakeTimeoutMillis;
        private ScheduledFuture<?> timeout;

        HandshakeHandler(boolean server, long handshakeTimeoutMillis) {
            this.server = server;
            this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        }

        @Override public void channelActive(ChannelHandlerContext ctx) {
            // Bound silent server-side peers that never send an HTTP Upgrade request.
            if (server && !complete) {
                timeout = ctx.executor().schedule(() -> {
                    fail(ctx, new WebSocketHandshakeException("WebSocket establishment timed out"));
                }, handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            ctx.fireChannelActive();
        }

        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                    || event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                cancelTimeout();
                complete = true;
                ctx.fireUserEventTriggered(event);
            } else if (event instanceof WebSocketServerProtocolHandler.ServerHandshakeStateEvent
                    || event instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent) {
                if (event == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT
                        || event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
                    cancelTimeout();
                    fail(ctx, new WebSocketHandshakeException("WebSocket handshake timed out"));
                }
            } else ctx.fireUserEventTriggered(event);
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof TooLongFrameException || cause instanceof CorruptedWebSocketFrameException) {
                if (!complete) ctx.fireExceptionCaught(cause);
                ctx.close();
            } else if (!complete) fail(ctx, cause);
            else ctx.fireExceptionCaught(cause);
        }

        private void fail(ChannelHandlerContext ctx, Throwable cause) {
            cancelTimeout();
            ctx.fireExceptionCaught(cause);
            ctx.close();
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
