package cn.managame.network.netty;

import cn.managame.network.*;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Tcp server using native Bootstrap and ChannelGroup lifecycle. */
public final class TcpNetworkServer implements NetworkServer {
    private final NetworkResources resources;
    private final boolean ownsResources;
    private final Settings settings;
    private final Supplier<? extends NetworkHandler> handlerFactory;
    private final BiConsumer<Connection, ChannelPipeline> pipelineInitializer;
    private final Map<String, InetSocketAddress> addresses;
    private final Map<ChannelOption<?>, Object> listenerOptions;
    private final Map<String, InetSocketAddress> bound = new ConcurrentHashMap<>();
    private final ChannelGroup channels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private volatile boolean stopped;

    private TcpNetworkServer(Builder b) {
        settings =
                new Settings(
                        b,
                        NetworkOptions.READ_IDLE,
                        NetworkOptions.WRITE_IDLE,
                        NetworkOptions.ALL_IDLE,
                        NetworkOptions.START_TIMEOUT,
                        NetworkOptions.STOP_TIMEOUT);
        handlerFactory = b.factory;
        pipelineInitializer = b.pipeline;
        if (b.addresses.isEmpty())
            throw new IllegalArgumentException("At least one listen address required");

        if (handlerFactory == null)
            throw new IllegalArgumentException("NetworkHandler factory required");
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
        var connection = new NettyConnection(ch, ConnectionType.TCP);
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

        if (pipelineInitializer != null) pipelineInitializer.accept(connection, pipeline);
        NetworkHandler handler =
                Objects.requireNonNull(
                        handlerFactory.get(), "NetworkHandler factory returned null");
        pipeline.addLast(
                "network.handler", new NetworkHandlerBridge(connection, handler, readiness));
    }

    public static final class Builder extends RealtimeServerBuilder<Builder> {
        public TcpNetworkServer build() {
            return new TcpNetworkServer(this);
        }
    }
}
