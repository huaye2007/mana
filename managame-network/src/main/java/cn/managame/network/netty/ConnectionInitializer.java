package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds the pipeline of every accepted connection: the transport (TCP, TLS, WebSocket), the application's
 * handlers and, last, the handler that drives the ConnectionHandler. It is a plain Netty child handler, holds
 * only configuration, and serves any number of listeners, a {@link NetworkServer} or an application's own
 * ServerBootstrap:
 *
 * <pre>{@code
 * ConnectionInitializer game = ConnectionInitializer.ws("/game")
 *         .addLast("codec", MyCodec::new)
 *         .handler(gameHandler)
 *         .build();
 * NetworkServer server = NetworkServer.start(9001, game);
 * }</pre>
 *
 * The pipeline layout is described in {@link PipelineNames}.
 */
public final class ConnectionInitializer extends ChannelInitializer<Channel> {
    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    private final SslContext ssl;
    private final WebSocketTransport webSocket;
    private final long handshakeTimeoutMillis;
    private final HandlerChain front, application;
    private final List<Consumer<ChannelPipeline>> customizers;
    private final ConnectionBridge bridge;

    private ConnectionInitializer(Builder b) {
        ssl = b.ssl;
        handshakeTimeoutMillis = b.handshakeTimeout.toMillis();
        webSocket = b.webSocket == null ? null : new WebSocketTransport(b.webSocket, handshakeTimeoutMillis);
        front = b.front.build();
        application = b.application.build();
        customizers = List.copyOf(b.customizers);
        bridge = new ConnectionBridge(b.handler, b.mapper);
    }

    /** Raw TCP: handlers receive the byte stream, so add framing such as {@link NetworkHandlers#lengthFieldFraming}. */
    public static Builder tcp() { return new Builder(null); }

    /** WebSocket with binary messages on an exact path. */
    public static Builder ws(String path) { return ws(WebSocketConfig.of(path)); }

    public static Builder ws(WebSocketConfig config) { return new Builder(Objects.requireNonNull(config, "config")); }

    /** WebSocket over TLS; the same as {@code ws(path).ssl(ssl)}. */
    public static Builder wss(String path, SslContext ssl) { return ws(path).ssl(ssl); }

    public static Builder wss(WebSocketConfig config, SslContext ssl) { return ws(config).ssl(ssl); }

    @Override protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        front.addTo(pipeline);
        if (ssl != null) {
            SslHandler tls = ssl.newHandler(channel.alloc());
            tls.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
            pipeline.addLast(PipelineNames.SSL, tls);
        }
        if (webSocket != null) webSocket.install(pipeline);
        application.addTo(pipeline);
        for (Consumer<ChannelPipeline> customizer : customizers) customizer.accept(pipeline);
        pipeline.addLast(PipelineNames.CONNECTION, bridge);
    }

    /** For example {@code tcp}, {@code tls}, {@code ws:/game} or {@code wss:/game}. */
    @Override public String toString() {
        if (webSocket == null) return ssl == null ? "tcp" : "tls";
        return (ssl == null ? "ws:" : "wss:") + webSocket.config().path();
    }

    /**
     * Handlers go to two sections of each pipeline:
     * <ul>
     * <li>{@code addFront}: ahead of TLS and WebSocket, seeing raw bytes, for example an IP filter, a HAProxy
     * decoder or traffic shaping;</li>
     * <li>{@code addLast}: after them, seeing whole WebSocket payloads or the TCP byte stream, for example
     * framing, codecs and idle detection, ending with the messages the ConnectionHandler receives.</li>
     * </ul>
     * Each section keeps declaration order. A {@code @Sharable} instance is reused by every connection; a
     * factory creates a handler per connection. {@link #customize} runs last with the whole pipeline.
     */
    public static final class Builder {
        private final WebSocketConfig webSocket;
        private SslContext ssl;
        private ConnectionHandler handler;
        private ConnectionMapper mapper = ConnectionMapper.attribute();
        private final HandlerChain.Builder front = HandlerChain.builder(), application = HandlerChain.builder();
        private final Set<String> names = new HashSet<>();
        private final List<Consumer<ChannelPipeline>> customizers = new ArrayList<>();
        private Duration handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT;

        private Builder(WebSocketConfig webSocket) { this.webSocket = webSocket; }

        /** Server TLS: turns ws into wss, or tcp into TLS over TCP. See {@link NetworkSsl}. */
        public Builder ssl(SslContext ssl) {
            if (!Objects.requireNonNull(ssl, "ssl").isServer()) throw new IllegalArgumentException("Server SslContext required");
            this.ssl = ssl;
            return this;
        }

        /** Required; shared by every connection. */
        public Builder handler(ConnectionHandler handler) { this.handler = Objects.requireNonNull(handler, "handler"); return this; }

        /** How channels map to connections, both creation and lookup; {@link ConnectionMapper#attribute()} by default. */
        public Builder connectionMapper(ConnectionMapper mapper) { this.mapper = Objects.requireNonNull(mapper, "mapper"); return this; }

        public Builder addFront(String name, ChannelHandler sharable) { front.addLast(name(name), sharable); return this; }
        public Builder addFront(String name, Supplier<? extends ChannelHandler> factory) { front.addLast(name(name), factory); return this; }
        public Builder addFront(HandlerChain chain) { chain.names().forEach(this::name); front.addLast(chain); return this; }

        public Builder addLast(String name, ChannelHandler sharable) { application.addLast(name(name), sharable); return this; }
        public Builder addLast(String name, Supplier<? extends ChannelHandler> factory) { application.addLast(name(name), factory); return this; }
        public Builder addLast(HandlerChain chain) { chain.names().forEach(this::name); application.addLast(chain); return this; }

        /**
         * Full access to each new pipeline after every other handler is installed and before the terminal
         * {@code connection} handler: insert around, replace or remove handlers by their {@link PipelineNames}.
         * Runs on the connection's EventLoop; an exception closes that connection.
         */
        public Builder customize(Consumer<ChannelPipeline> customizer) {
            customizers.add(Objects.requireNonNull(customizer, "customizer"));
            return this;
        }

        /**
         * Netty's own TLS handshake timeout and WebSocket handshake timeout, 10 seconds by default. The WebSocket
         * one starts when the upgrade request arrives, so a client that sends nothing is closed only by a read
         * timeout such as {@link NetworkHandlers#readTimeout}.
         */
        public Builder handshakeTimeout(Duration timeout) {
            if (timeout.toMillis() <= 0) throw new IllegalArgumentException("handshakeTimeout must be at least one millisecond");
            handshakeTimeout = timeout;
            return this;
        }

        public ConnectionInitializer build() {
            if (handler == null) throw new IllegalStateException("handler is required");
            return new ConnectionInitializer(this);
        }

        private String name(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Handler name must not be blank");
            if (PipelineNames.RESERVED.contains(name)) throw new IllegalArgumentException("Reserved handler name: " + name);
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate handler name: " + name);
            return name;
        }
    }
}
