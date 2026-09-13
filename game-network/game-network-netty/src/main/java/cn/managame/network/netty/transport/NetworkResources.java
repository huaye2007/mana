package cn.managame.network.netty.transport;

import cn.managame.network.*;

import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.*;
import io.netty.channel.socket.nio.*;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

/** Supplied resources are borrowed. Close after Server.stop() and Client.destroy(). */
public final class NetworkResources implements AutoCloseable {
    final ChannelFactory<? extends ServerChannel> serverChannel;
    final ChannelFactory<? extends Channel> clientChannel;
    private final ChannelFactory<? extends DatagramChannel> datagramChannel;
    private final IoHandlerFactory ioHandler;
    private final int ioThreads, httpThreads;
    private final Duration shutdownTimeout;
    private final List<EventLoopGroup> owned = new ArrayList<>();

    private final AtomicBoolean closed = new AtomicBoolean();
    private final BiConsumer<String, Throwable> diagnostics;
    private EventLoopGroup acceptor, io, http;
    private AddressResolverGroup<InetSocketAddress> resolver;
    private final boolean ownResolver;

    private NetworkResources(Builder b) {
        acceptor = b.acceptor;
        io = b.io;
        http = b.http;
        ioThreads = b.ioThreads;
        httpThreads = b.httpThreads;
        shutdownTimeout = b.shutdownTimeout;
        ioHandler = b.ioHandler;
        serverChannel = b.serverChannel;
        clientChannel = b.clientChannel;
        datagramChannel = b.datagramChannel;
        resolver = b.resolver;
        ownResolver = resolver == null;
        diagnostics = b.diagnostics;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NetworkResources create() {
        return builder().build();
    }

    synchronized EventLoopGroup acceptor() {
        ensureOpen();
        if (acceptor == null) acceptor = create(1, "game-accept");
        return acceptor;
    }

    synchronized EventLoopGroup io() {
        ensureOpen();
        if (io == null) io = create(ioThreads, "game-io");
        return io;
    }

    synchronized EventLoopGroup http() {
        ensureOpen();
        if (http == null) http = create(httpThreads, "game-http");
        return http;
    }

    private EventLoopGroup create(int count, String name) {
        var group =
                new MultiThreadIoEventLoopGroup(count, new DefaultThreadFactory(name), ioHandler);
        owned.add(group);
        return group;
    }

    synchronized AddressResolverGroup<InetSocketAddress> resolver() {
        ensureOpen();
        if (resolver == null)
            resolver =
                    new DnsAddressResolverGroup(
                            new DnsNameResolverBuilder().datagramChannelFactory(datagramChannel));
        return resolver;
    }

    void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Network resources closed");
    }

    void diagnose(String message, Throwable cause) {
        try {
            diagnostics.accept(message, cause);
        } catch (Throwable secondary) {
            System.getLogger(NetworkResources.class.getName())
                    .log(System.Logger.Level.ERROR, message, secondary);
        }
    }

    synchronized void checkControlThread() {
        for (var g : Arrays.asList(acceptor, io, http))
            if (g != null)
                for (var e : g)
                    if (e.inEventLoop())
                        throw new IllegalStateException(
                                "Synchronous lifecycle cannot block a network EventLoop");
    }

    @Override
    public void close() {
        close(shutdownTimeout);
    }

    /** Override the resource shutdown wait for this call. May be retried after a timeout. */
    public void close(Duration timeout) {
        validateShutdownTimeout(timeout);
        checkControlThread();
        List<EventLoopGroup> groups;
        synchronized (this) {
            closed.set(true);
            if (ownResolver && resolver != null) resolver.close();
            groups = List.copyOf(owned);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (var g : groups)
            g.shutdownGracefully(
                    0, Math.max(1, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        for (var g : groups)
            Waits.netty(g.terminationFuture(), deadline, "network resource shutdown");
    }

    public boolean isClosed() {
        return closed.get();
    }

    private static void validateShutdownTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "shutdownTimeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(365)) > 0)
            throw new IllegalArgumentException(
                    "shutdownTimeout must be positive and at most 365 days");
    }

    public static final class Builder {
        private EventLoopGroup acceptor, io, http;
        private int ioThreads = Math.max(1, Runtime.getRuntime().availableProcessors()),
                httpThreads = ioThreads;
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private IoHandlerFactory ioHandler = NioIoHandler.newFactory();
        private ChannelFactory<? extends ServerChannel> serverChannel = NioServerSocketChannel::new;
        private ChannelFactory<? extends Channel> clientChannel = NioSocketChannel::new;
        private ChannelFactory<? extends DatagramChannel> datagramChannel = NioDatagramChannel::new;
        private AddressResolverGroup<InetSocketAddress> resolver;
        private BiConsumer<String, Throwable> diagnostics =
                (m, t) ->
                        System.getLogger("cn.managame.network")
                                .log(System.Logger.Level.ERROR, m, t);

        public Builder ioThreads(int n) {
            if (n < 1) throw new IllegalArgumentException("ioThreads");
            ioThreads = n;
            return this;
        }

        /** Wait for owned EventLoopGroups to terminate; default is five seconds. */
        public Builder shutdownTimeout(Duration timeout) {
            validateShutdownTimeout(timeout);
            shutdownTimeout = timeout;
            return this;
        }

        public Builder httpThreads(int n) {
            if (n < 1) throw new IllegalArgumentException("httpThreads");
            httpThreads = n;
            return this;
        }

        public Builder ioGroup(EventLoopGroup g) {
            io = Objects.requireNonNull(g);
            return this;
        }

        public Builder httpGroup(EventLoopGroup g) {
            http = Objects.requireNonNull(g);
            return this;
        }

        public Builder acceptorGroup(EventLoopGroup g) {
            acceptor = Objects.requireNonNull(g);
            return this;
        }

        public Builder resolver(AddressResolverGroup<InetSocketAddress> r) {
            resolver = Objects.requireNonNull(r);
            return this;
        }

        public Builder diagnostics(BiConsumer<String, Throwable> d) {
            diagnostics = Objects.requireNonNull(d);
            return this;
        }

        public Builder transport(
                IoHandlerFactory factory,
                ChannelFactory<? extends ServerChannel> server,
                ChannelFactory<? extends Channel> client,
                ChannelFactory<? extends DatagramChannel> datagram) {
            ioHandler = Objects.requireNonNull(factory);
            serverChannel = Objects.requireNonNull(server);
            clientChannel = Objects.requireNonNull(client);
            datagramChannel = Objects.requireNonNull(datagram);
            return this;
        }

        public NetworkResources build() {
            if (io != null && io == http)
                throw new IllegalArgumentException("HTTP and realtime groups must be distinct");
            return new NetworkResources(this);
        }
    }
}
