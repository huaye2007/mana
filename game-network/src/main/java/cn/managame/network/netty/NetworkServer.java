package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.error.NetworkException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** One-shot synchronous listener. Externally supplied EventLoopGroups remain caller-owned. */
public final class NetworkServer implements AutoCloseable {
    private final SocketAddress address;
    private final ConnectionHandler handler;
    private final List<Consumer<ChannelPipeline>> pipelines;
    private final String path;
    private final int maxMessageSize;
    private final SslContext ssl;
    private final ChannelFactory<? extends ServerChannel> factory;
    private final Map<ChannelOption<?>, Object> options, childOptions;
    private final boolean ownBoss, ownWorker;
    private volatile EventLoopGroup boss, worker;
    private volatile Channel serverChannel;
    private volatile boolean closed;
    private boolean started;
    // Only transport handshakes, never established business connections.
    private final Set<Channel> pending = ConcurrentHashMap.newKeySet();

    NetworkServer(NetworkServerBuilder b) {
        address = b.address; handler = b.handler; pipelines = List.copyOf(b.pipelines);
        path = b.path; maxMessageSize = b.maxMessageSize; ssl = b.ssl; factory = b.factory;
        options = Map.copyOf(b.options); childOptions = Map.copyOf(b.childOptions);
        boss = b.boss; worker = b.worker; ownBoss = boss == null; ownWorker = worker == null;
    }
    public static NetworkServerBuilder builder() { return new NetworkServerBuilder(); }

    public void start() {
        checkThread();
        synchronized (this) {
            if (started || closed) throw new IllegalStateException("Server already started or closed");
            started = true;
            ChannelFuture bind = null;
            try {
                if (ownBoss) boss = new NioEventLoopGroup(1);
                if (ownWorker) worker = new NioEventLoopGroup();
                ServerBootstrap bootstrap = new ServerBootstrap().group(boss, worker).channelFactory(factory);
                applyOptions(bootstrap);
                bootstrap.childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel ch) {
                        pending.add(ch);
                        ch.closeFuture().addListener(f -> pending.remove(ch));
                        if (closed) { ch.close(); return; }
                        try {
                            NetworkPipeline.install(ch, handler, pipelines, ssl, null, -1,
                                    path != null, path, null, null, maxMessageSize, null,
                                    () -> !closed, () -> pending.remove(ch));
                        } catch (Throwable cause) {
                            NetworkSupport.log("Server pipeline initialization failed", cause);
                            ch.close();
                        }
                    }
                });
                bind = bootstrap.bind(address);
                serverChannel = bind.channel();
                bind.sync();
            } catch (Throwable cause) {
                closed = true;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                try { cleanup(); } catch (Throwable cleanup) { cause.addSuppressed(cleanup); }
                throw new NetworkException("Server bind failed: " + address, cause);
            }
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
            closed = true;
            cleanup();
            NetworkSupport.checkInterrupted("Server close");
        }
    }
    private void checkThread() {
        NetworkSupport.checkNotEventLoop(boss);
        NetworkSupport.checkNotEventLoop(worker);
    }
    private void cleanup() {
        // Each resource gets its cleanup attempt even if a previous one fails.
        try {
            if (serverChannel != null) NetworkSupport.await(serverChannel.close(), "Listener close failed");
        } finally {
            try {
                for (Channel channel : pending) channel.close();
            } finally {
                try { if (ownBoss && boss != null) NetworkSupport.shutdown(boss); }
                finally { if (ownWorker && worker != null && worker != boss) NetworkSupport.shutdown(worker); }
            }
        }
    }
}