package cn.managame.network.netty;

import cn.managame.network.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.*;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Multi-target TCP client sharing one configured Bootstrap. */
public final class TcpNetworkClient implements NetworkClient {
    private static final io.netty.util.AttributeKey<Promise<Connection>> CONNECT =
            io.netty.util.AttributeKey.valueOf(TcpNetworkClient.class, "connect");
    private final NetworkResources resources;
    private final boolean ownsResources;
    private final Settings settings;
    private final Supplier<? extends NetworkHandler> handlerFactory;
    private final BiConsumer<Connection, ChannelPipeline> pipelineInitializer;
    private final ChannelGroup channels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    Bootstrap bootstrap;
    private volatile boolean initialized, destroyed;

    private TcpNetworkClient(Builder b) {
        if (b.factory == null)
            throw new IllegalArgumentException("NetworkHandler factory required");
        settings =
                new Settings(
                        b,
                        NetworkOptions.READ_IDLE,
                        NetworkOptions.WRITE_IDLE,
                        NetworkOptions.ALL_IDLE,
                        NetworkOptions.DESTROY_TIMEOUT);
        handlerFactory = b.factory;
        pipelineInitializer = b.pipeline;
        ownsResources = b.resources == null;
        resources = ownsResources ? NetworkResources.create() : b.resources;
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

    public void connect(String host, int port, ConnectCallback callback) {
        if (host == null
                || host.isBlank()
                || host.contains("/")
                || host.contains("[")
                || host.contains("]")
                || host.chars().anyMatch(Character::isWhitespace)
                || port < 1
                || port > 65535) throw new IllegalArgumentException("Valid host and port required");
        if (host.equals("0.0.0.0") || host.equals("::"))
            throw new IllegalArgumentException("Wildcard is not a remote target");
        var address = InetSocketAddress.createUnresolved(host, port);
        Objects.requireNonNull(callback, "callback");
        if (destroyed) throw new NetworkException("Client destroyed");
        if (!initialized) throw new NetworkException("Client not initialized");
        resources.ensureOpen();
        var future = bootstrap.connect(address);
        Channel ch = future.channel();
        if (ch.isOpen()) channels.add(ch);
        future.addListener(
                f -> {
                    // The initializer creates this result on the Channel's assigned EventLoop.
                    var readiness = ch.attr(CONNECT).getAndSet(null);
                    if (readiness == null) {
                        notifyConnect(
                                callback,
                                null,
                                f.cause() != null
                                        ? f.cause()
                                        : new NetworkException(
                                                "Channel initialization did not complete"));
                        return;
                    }
                    if (!f.isSuccess()) readiness.tryFailure(f.cause());
                    ch.closeFuture()
                            .addListener(
                                    closed -> readiness.tryFailure(new ClosedChannelException()));
                    readiness.addListener(
                            result -> {
                                if (!result.isSuccess()) ch.close();
                                notifyConnect(callback, readiness.getNow(), result.cause());
                            });
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
                        .resolver(resources.resolver())
                        .handler(
                                new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel ch) {
                                        Promise<Connection> result = ch.eventLoop().newPromise();
                                        ch.attr(CONNECT).set(result);
                                        try {
                                            initPipeline(ch, result);
                                        } catch (Throwable failure) {
                                            result.tryFailure(failure);
                                            ch.close();
                                        }
                                    }
                                });
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

    private void initPipeline(Channel ch, Promise<Connection> readiness) {
        var connection = new NettyConnection(ch, ConnectionType.TCP);
        ChannelPipeline pipeline = ch.pipeline();
        long read = settings.get(NetworkOptions.READ_IDLE).toNanos();
        long write = settings.get(NetworkOptions.WRITE_IDLE).toNanos();
        long all = settings.get(NetworkOptions.ALL_IDLE).toNanos();
        if (read != 0 || write != 0 || all != 0)
            pipeline.addLast(
                    "network.idle", new IdleStateHandler(read, write, all, TimeUnit.NANOSECONDS));

        if (pipelineInitializer != null) pipelineInitializer.accept(connection, pipeline);
        NetworkHandler handler =
                Objects.requireNonNull(
                        handlerFactory.get(), "NetworkHandler factory returned null");
        pipeline.addLast(
                "network.handler", new NetworkHandlerBridge(connection, handler, readiness));
    }

    public static final class Builder extends ClientBuilder<Builder> {
        public TcpNetworkClient build() {
            return new TcpNetworkClient(this);
        }
    }
}
