package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.error.NetworkException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.SocketAddress;
import java.util.*;
import java.util.function.Consumer;

/** One-shot synchronous listener. Externally supplied EventLoopGroups remain caller-owned. */
public final class NetworkServer implements AutoCloseable {
    private final SocketAddress address;
    private final ConnectionHandler handler;
    private final List<Consumer<ChannelPipeline>> pipelines;
    private final ChannelTransport transport;
    private final ChannelFactory<? extends ServerChannel> factory;
    private final Map<ChannelOption<?>, Object> options, childOptions;
    private final boolean ownBoss, ownWorker;
    private volatile EventLoopGroup boss, worker;
    private volatile Channel serverChannel;
    private volatile boolean closed;
    private boolean started;
    // Only transport handshakes, never established business connections.
    private final Set<Channel> pending = new HashSet<>();

    NetworkServer(NetworkServerBuilder b) {
        address = b.address; handler = b.handler; pipelines = List.copyOf(b.pipelines);
        ChannelTransport payload = b.path == null
                ? ChannelTransport.TCP : WebSocketTransport.server(b.path, b.maxMessageSize);
        transport = b.ssl == null ? payload : TlsTransport.server(b.ssl, payload);
        factory = b.factory;
        options = Map.copyOf(b.options); childOptions = Map.copyOf(b.childOptions);
        boss = b.boss; worker = b.worker; ownBoss = boss == null; ownWorker = worker == null;
    }
    public static NetworkServerBuilder builder() { return new NetworkServerBuilder(); }

    public void start() {
        checkThread();
        synchronized (this) {
            if (started || closed) throw new IllegalStateException("Server already started or closed");
            started = true;
            try {
                if (ownBoss) boss = new NioEventLoopGroup(1);
                if (ownWorker) worker = new NioEventLoopGroup();
                ServerBootstrap bootstrap = new ServerBootstrap().group(boss, worker).channelFactory(factory);
                applyOptions(bootstrap);
                bootstrap.childHandler(new NetworkChannelInitializer(handler, pipelines, transport, this::accept));
                ChannelFuture bind = bootstrap.bind(address);
                serverChannel = bind.channel();
                bind.sync();
            } catch (Throwable cause) {
                stopAccepting();
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                try { cleanup(); } catch (Throwable cleanup) { cause.addSuppressed(cleanup); }
                throw new NetworkException("Server bind failed: " + address, cause);
            }
        }
    }
    private ConnectionEstablishment accept(Channel channel) {
        AcceptedConnection establishment = new AcceptedConnection(channel);
        synchronized (pending) {
            if (!closed) pending.add(channel);
        }
        channel.closeFuture().addListener(establishment);
        if (closed) channel.close();
        return establishment;
    }

    private void stopAccepting() {
        synchronized (pending) {
            closed = true;
        }
    }

    /** Tracks an accepted channel only until transport establishment has a result. */
    private final class AcceptedConnection implements ConnectionEstablishment, ChannelFutureListener {
        private final Channel channel;

        AcceptedConnection(Channel channel) {
            this.channel = channel;
        }

        @Override public boolean claimSuccess() {
            synchronized (pending) {
                if (closed) return false;
                // Claim and removal are atomic with stopping admission, before business delivery.
                pending.remove(channel);
                return true;
            }
        }

        @Override public void success(Connection connection) {}

        @Override public void networkFailure(Throwable cause) {
            NetworkSupport.log("Server transport establishment failed", cause);
        }

        @Override public void release() {
            synchronized (pending) {
                pending.remove(channel);
            }
            channel.closeFuture().removeListener(this);
        }

        @Override public void operationComplete(ChannelFuture future) {
            release();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyOptions(ServerBootstrap bootstrap) {
        options.forEach((key, value) -> bootstrap.option((ChannelOption) key, value));
        childOptions.forEach((key, value) -> bootstrap.childOption((ChannelOption) key, value));
    }
    public SocketAddress localAddress() {
        Channel channel = serverChannel;
        return channel == null ? null : channel.localAddress();
    }
    @Override public void close() {
        if (closed) return;
        checkThread();
        synchronized (this) {
            if (closed) return;
            stopAccepting();
            cleanup();
            NetworkSupport.checkInterrupted("Server close");
        }
    }
    private void checkThread() {
        NetworkSupport.checkNotEventLoop(boss);
        NetworkSupport.checkNotEventLoop(worker);
    }
    private void closePending() {
        List<Channel> channels;
        synchronized (pending) {
            channels = List.copyOf(pending);
            pending.clear();
        }
        for (Channel channel : channels) channel.close();
    }

    private void cleanup() {
        // Each resource gets its cleanup attempt even if a previous one fails.
        try {
            if (serverChannel != null) NetworkSupport.await(serverChannel.close(), "Listener close failed");
        } finally {
            try {
                closePending();
            } finally {
                try { if (ownBoss && boss != null) NetworkSupport.shutdown(boss); }
                finally { if (ownWorker && worker != null && worker != boss) NetworkSupport.shutdown(worker); }
            }
        }
    }
}