package cn.managame.network.netty.transport;

import cn.managame.network.netty.connection.NettyConnection;
import cn.managame.network.netty.connection.NettyAccess;

import cn.managame.network.*;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Ws server using native Bootstrap and ChannelGroup lifecycle. */
public final class WsNetworkServer implements NetworkServer {
    private final NetworkResources resources;
    private final boolean ownsResources;
    private final Settings settings;
    private final Supplier<? extends NetworkHandler> handlerFactory;
    private final BiConsumer<Connection, ChannelPipeline> pipelineInitializer;
    private final SslContext sslContext;
    private final Consumer<SslHandler> tlsHandlerCustomizer;
    private final Consumer<WebSocketServerProtocolConfig.Builder> webSocketCustomizer;
    private final Map<String, InetSocketAddress> addresses;
    private final Map<ChannelOption<?>, Object> listenerOptions;
    private final Map<String, InetSocketAddress> bound = new ConcurrentHashMap<>();
    private final ChannelGroup channels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private volatile boolean stopped;

    private WsNetworkServer(Builder b) {
        settings =
                new Settings(
                        b,
                        NetworkOptions.READ_IDLE,
                        NetworkOptions.WRITE_IDLE,
                        NetworkOptions.ALL_IDLE,
                        NetworkOptions.START_TIMEOUT,
                        NetworkOptions.STOP_TIMEOUT,
                        NetworkOptions.WS_AGGREGATION,
                        NetworkOptions.WS_UPGRADE_AGGREGATION);
        handlerFactory = b.factory;
        pipelineInitializer = b.pipeline;
        sslContext = b.ssl;
        tlsHandlerCustomizer = b.tlsHandler;
        webSocketCustomizer = b.wsServer;
        if (b.addresses.isEmpty())
            throw new IllegalArgumentException("At least one listen address required");

        if (handlerFactory == null)
            throw new IllegalArgumentException("NetworkHandler factory required");
        if (sslContext != null && !sslContext.isServer())
            throw new IllegalArgumentException("Server SslContext required");
        if (sslContext == null && tlsHandlerCustomizer != null)
            throw new IllegalArgumentException("tlsHandler requires sslContext");
        ownsResources = b.resources == null;
        resources = ownsResources ? NetworkResources.create() : b.resources;
        addresses = Collections.unmodifiableMap(new LinkedHashMap<>(b.addresses));
        listenerOptions = Map.copyOf(b.listenerOptions);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, InetSocketAddress> boundAddresses() {
        return Map.copyOf(bound);
    }

    @Override
    public void start() {
        resources.checkControlThread();
        synchronized (this) {
            if (stopped) throw new IllegalStateException("Server stopped");
            if (!bound.isEmpty()) return;
            try {
                var bootstrap =
                        new ServerBootstrap()
                                .group(resources.acceptor(), resources.io())
                                .channelFactory(resources.serverChannel)
                                .childHandler(
                                        new ChannelInitializer<Channel>() {
                                            @Override
                                            protected void initChannel(Channel ch)
                                                    throws Exception {
                                                channels.add(ch);
                                                if (stopped) {
                                                    ch.close();
                                                    return;
                                                }
                                                initPipeline(ch);
                                            }
                                        });
                configure(bootstrap);
                long deadline =
                        System.nanoTime() + settings.get(NetworkOptions.START_TIMEOUT).toNanos();
                for (var entry : addresses.entrySet()) {
                    var bind = bootstrap.bind(entry.getValue());
                    if (bind.channel().isOpen()) channels.add(bind.channel());
                    Waits.netty(bind, deadline, "bind " + entry.getKey());
                    bound.put(entry.getKey(), (InetSocketAddress) bind.channel().localAddress());
                }
            } catch (RuntimeException failure) {
                boolean interrupted = Thread.interrupted();
                try {
                    stop();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
                throw failure;
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void configure(ServerBootstrap bootstrap) {
        listenerOptions.forEach((key, value) -> bootstrap.option((ChannelOption) key, value));
        settings.nativeOptions.forEach(
                (key, value) -> bootstrap.childOption((ChannelOption) key, value));
    }

    @Override
    public void stop() {
        resources.checkControlThread();
        synchronized (this) {
            stopped = true;
            Waits.netty(
                    channels.close(),
                    System.nanoTime() + settings.get(NetworkOptions.STOP_TIMEOUT).toNanos(),
                    "stop");
            if (ownsResources) resources.close();
        }
    }

    private void initPipeline(Channel ch) {
        var connection =
                new NettyConnection(
                        ch, sslContext != null ? ConnectionType.WSS : ConnectionType.WS);
        ChannelPipeline pipeline = ch.pipeline();
        Promise<Connection> readiness = ch.eventLoop().newPromise();
        readiness.addListener(
                f -> {
                    if (!f.isSuccess()) {
                        resources.diagnose("Connection setup failed", f.cause());
                        ch.close();
                    }
                });
        long read = settings.get(NetworkOptions.READ_IDLE).toNanos();
        long write = settings.get(NetworkOptions.WRITE_IDLE).toNanos();
        long all = settings.get(NetworkOptions.ALL_IDLE).toNanos();
        if (read != 0 || write != 0 || all != 0)
            pipeline.addLast(
                    "network.idle", new IdleStateHandler(read, write, all, TimeUnit.NANOSECONDS));

        if (sslContext != null) {
            SslHandler tls = sslContext.newHandler(ch.alloc());
            if (tlsHandlerCustomizer != null) tlsHandlerCustomizer.accept(tls);
            pipeline.addLast("network.tls", tls);
            tls.handshakeFuture()
                    .addListener(
                            f -> {
                                if (!f.isSuccess()) readiness.tryFailure(f.cause());
                            });
        }
        var config = WebSocketServerProtocolConfig.newBuilder().websocketPath("/");
        if (webSocketCustomizer != null) webSocketCustomizer.accept(config);
        pipeline.addLast("network.httpCodec", new HttpServerCodec());
        pipeline.addLast(
                "network.httpAggregate",
                new HttpObjectAggregator(settings.get(NetworkOptions.WS_UPGRADE_AGGREGATION)));
        pipeline.addLast("network.websocket", new WebSocketServerProtocolHandler(config.build()));
        if (settings.get(NetworkOptions.WS_AGGREGATION) > 0)
            pipeline.addLast(
                    "network.websocketAggregate",
                    new WebSocketFrameAggregator(settings.get(NetworkOptions.WS_AGGREGATION)));

        if (pipelineInitializer != null) pipelineInitializer.accept(connection, pipeline);
        NetworkHandler handler =
                Objects.requireNonNull(
                        handlerFactory.get(), "NetworkHandler factory returned null");
        pipeline.addLast(
                "network.handler", NettyAccess.handler(connection, handler, readiness));
    }

    public static final class Builder extends RealtimeServerBuilder<Builder> {
        private SslContext ssl;
        private Consumer<SslHandler> tlsHandler;
        private Consumer<WebSocketServerProtocolConfig.Builder> wsServer;

        public Builder sslContext(SslContext value) {
            ssl = Objects.requireNonNull(value);
            return this;
        }

        public Builder tlsHandler(Consumer<SslHandler> value) {
            tlsHandler = Objects.requireNonNull(value);
            return this;
        }

        public Builder webSocketServer(Consumer<WebSocketServerProtocolConfig.Builder> value) {
            wsServer = Objects.requireNonNull(value);
            return this;
        }

        public WsNetworkServer build() {
            return new WsNetworkServer(this);
        }
    }
}
