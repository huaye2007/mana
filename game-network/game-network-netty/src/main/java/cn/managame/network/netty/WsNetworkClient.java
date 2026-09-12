package cn.managame.network.netty;

import cn.managame.network.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.handler.address.ResolveAddressHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.*;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Multi-target Ws client with one shared Bootstrap. */
public final class WsNetworkClient implements NetworkClient {
    private final NetworkResources resources;
    private final boolean ownsResources;
    private final Settings settings;
    private final Supplier<? extends NetworkHandler> handlerFactory;
    private final BiConsumer<Connection, ChannelPipeline> pipelineInitializer;
    private final SslContext configuredSslContext;
    private SslContext sslContext;
    private final Consumer<SslHandler> tlsHandlerCustomizer;
    private final Consumer<WebSocketClientProtocolConfig.Builder> webSocketCustomizer;
    private final ChannelGroup channels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    Bootstrap bootstrap;
    private volatile boolean initialized, destroyed;

    private WsNetworkClient(Builder b) {
        if (b.factory == null)
            throw new IllegalArgumentException("NetworkHandler factory required");
        if (b.ssl != null && !b.ssl.isClient())
            throw new IllegalArgumentException("Client SslContext required");
        settings =
                new Settings(
                        b,
                        NetworkOptions.READ_IDLE,
                        NetworkOptions.WRITE_IDLE,
                        NetworkOptions.ALL_IDLE,
                        NetworkOptions.DESTROY_TIMEOUT,
                        NetworkOptions.WS_AGGREGATION,
                        NetworkOptions.WS_UPGRADE_AGGREGATION);
        handlerFactory = b.factory;
        pipelineInitializer = b.pipeline;
        configuredSslContext = b.ssl;
        tlsHandlerCustomizer = b.tlsHandler;
        webSocketCustomizer = b.wsClient;
        ownsResources = b.resources == null;
        resources = ownsResources ? NetworkResources.create() : b.resources;
    }

    private static SslContext defaultSslContext() {
        try {
            return SslContextBuilder.forClient().build();
        } catch (javax.net.ssl.SSLException e) {
            throw new NetworkException("Cannot initialize client TLS", e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void init() {
        resources.checkControlThread();
        synchronized (this) {
            if (destroyed) throw new NetworkException("Client destroyed");
            if (initialized) return;
            try {
                sslContext =
                        configuredSslContext == null ? defaultSslContext() : configuredSslContext;
                bootstrap = createBootstrap();
                initialized = true;
            } catch (RuntimeException failure) {
                try {
                    destroy();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    public void connect(URI uri, ConnectCallback callback) {
        Objects.requireNonNull(uri, "uri");
        if ((!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535)
            throw new IllegalArgumentException("Expected ws/wss URI with host");
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        var address = InetSocketAddress.createUnresolved(host, remotePort(uri));
        Objects.requireNonNull(callback, "callback");
        if (destroyed) throw new NetworkException("Client destroyed");
        if (!initialized) throw new NetworkException("Client not initialized");
        resources.ensureOpen();
        var registration = bootstrap.register();
        Channel ch = registration.channel();
        registration.addListener(
                registered -> {
                    if (!registered.isSuccess()) {
                        notifyConnect(callback, null, registered.cause());
                        return;
                    }
                    Promise<Connection> result = ch.eventLoop().newPromise();
                    result.addListener(
                            f -> {
                                if (!f.isSuccess()) ch.close();
                                notifyConnect(callback, result.getNow(), f.cause());
                            });
                    if (destroyed) {
                        result.tryFailure(new NetworkException("Client destroyed"));
                        return;
                    }
                    channels.add(ch);
                    ch.closeFuture()
                            .addListener(f -> result.tryFailure(new ClosedChannelException()));
                    if (result.isDone()) return;
                    try {
                        // Install this URI's handlers before native channelActive starts
                        // handshaking.
                        initPipeline(ch, uri, result);
                        ch.connect(address)
                                .addListener(
                                        f -> {
                                            if (!f.isSuccess()) result.tryFailure(f.cause());
                                        });
                    } catch (Throwable failure) {
                        result.tryFailure(failure);
                    }
                });
    }

    private void notifyConnect(ConnectCallback callback, Connection connection, Throwable failure) {
        try {
            if (failure == null) callback.onSuccess(connection);
            else callback.onFailure(failure);
        } catch (Throwable callbackFailure) {
            resources.diagnose("ConnectCallback failed", callbackFailure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Bootstrap createBootstrap() {
        var bootstrap =
                new Bootstrap()
                        .group(resources.io())
                        .channelFactory(resources.clientChannel)
                        .handler(new ResolveAddressHandler(resources.resolver()));
        settings.nativeOptions.forEach(
                (option, value) -> bootstrap.option((ChannelOption) option, value));
        return bootstrap;
    }

    @Override
    public void destroy() {
        resources.checkControlThread();
        synchronized (this) {
            destroyed = true;
            Waits.netty(
                    channels.close(),
                    System.nanoTime() + settings.get(NetworkOptions.DESTROY_TIMEOUT).toNanos(),
                    "destroy");
            if (ownsResources) resources.close();
        }
    }

    private void initPipeline(Channel ch, URI uri, Promise<Connection> readiness) {
        var connection =
                new NettyConnection(
                        ch, "wss".equals(uri.getScheme()) ? ConnectionType.WSS : ConnectionType.WS);
        ChannelPipeline pipeline = ch.pipeline();
        long read = settings.get(NetworkOptions.READ_IDLE).toNanos();
        long write = settings.get(NetworkOptions.WRITE_IDLE).toNanos();
        long all = settings.get(NetworkOptions.ALL_IDLE).toNanos();
        if (read != 0 || write != 0 || all != 0)
            pipeline.addLast(
                    "network.idle", new IdleStateHandler(read, write, all, TimeUnit.NANOSECONDS));

        if ("wss".equals(uri.getScheme())) {
            SslHandler tls = sslContext.newHandler(ch.alloc(), uri.getHost(), remotePort(uri));
            if (tlsHandlerCustomizer != null) tlsHandlerCustomizer.accept(tls);
            var parameters = tls.engine().getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.engine().setSSLParameters(parameters);
            pipeline.addLast("network.tls", tls);
            tls.handshakeFuture()
                    .addListener(
                            f -> {
                                if (!f.isSuccess()) readiness.tryFailure(f.cause());
                            });
        }
        var config = WebSocketClientProtocolConfig.newBuilder().webSocketUri(uri);
        if (webSocketCustomizer != null) webSocketCustomizer.accept(config);
        config.webSocketUri(uri);
        pipeline.addLast("network.httpCodec", new HttpClientCodec());
        pipeline.addLast(
                "network.httpAggregate",
                new HttpObjectAggregator(settings.get(NetworkOptions.WS_UPGRADE_AGGREGATION)));
        pipeline.addLast("network.websocket", new WebSocketClientProtocolHandler(config.build()));
        if (settings.get(NetworkOptions.WS_AGGREGATION) > 0)
            pipeline.addLast(
                    "network.websocketAggregate",
                    new WebSocketFrameAggregator(settings.get(NetworkOptions.WS_AGGREGATION)));

        if (pipelineInitializer != null) pipelineInitializer.accept(connection, pipeline);
        NetworkHandler handler =
                Objects.requireNonNull(
                        handlerFactory.get(), "NetworkHandler factory returned null");
        pipeline.addLast(
                "network.handler", new NetworkHandlerBridge(connection, handler, readiness));
    }

    private static int remotePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : "wss".equals(uri.getScheme()) ? 443 : 80;
    }

    public static final class Builder extends ClientBuilder<Builder> {
        private SslContext ssl;
        private Consumer<SslHandler> tlsHandler;
        private Consumer<WebSocketClientProtocolConfig.Builder> wsClient;

        public Builder sslContext(SslContext value) {
            ssl = Objects.requireNonNull(value);
            return this;
        }

        public Builder tlsHandler(Consumer<SslHandler> value) {
            tlsHandler = Objects.requireNonNull(value);
            return this;
        }

        public Builder webSocketClient(Consumer<WebSocketClientProtocolConfig.Builder> value) {
            wsClient = Objects.requireNonNull(value);
            return this;
        }

        public WsNetworkClient build() {
            return new WsNetworkClient(this);
        }
    }
}
