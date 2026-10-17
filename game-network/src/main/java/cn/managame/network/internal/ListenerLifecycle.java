package cn.managame.network.internal;

import cn.managame.network.error.NetworkException;
import cn.managame.network.netty.NetworkTransports;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shared one-shot listener lifecycle of NetworkServer and HttpServer: owned or caller-supplied groups,
 * synchronous bind, idempotent close that finishes cleanup even when interrupted, and rejection of blocking
 * lifecycle calls from the listener's own threads. Internal; not an application API.
 */
public final class ListenerLifecycle {
    private final String name;
    private final SocketAddress address;
    private final ChannelFactory<? extends ServerChannel> factory;
    private final Map<ChannelOption<?>, Object> options, childOptions;
    private final EventExecutorGroup extraExecutor;
    private final boolean ownBoss, ownWorker;
    private volatile EventLoopGroup boss, worker;
    private volatile Channel listener;
    private volatile boolean closed;
    private boolean started;

    /**
     * @param name listener name used in failure messages, for example "Server" or "HTTP server"
     * @param factory listener channel type, or null to match the boss group's transport
     * @param boss caller-owned boss group, or null to own a one-thread group
     * @param worker caller-owned worker group, or null to own a default-size group
     * @param extraExecutor another caller-owned executor whose threads must not block on lifecycle calls, or null
     */
    public ListenerLifecycle(String name, SocketAddress address, ChannelFactory<? extends ServerChannel> factory,
                             Map<ChannelOption<?>, Object> options, Map<ChannelOption<?>, Object> childOptions,
                             EventLoopGroup boss, EventLoopGroup worker, EventExecutorGroup extraExecutor) {
        this.name = name;
        this.address = address;
        this.factory = factory;
        this.options = Map.copyOf(options);
        this.childOptions = Map.copyOf(childOptions);
        this.boss = boss;
        this.worker = worker;
        this.extraExecutor = extraExecutor;
        ownBoss = boss == null;
        ownWorker = worker == null;
    }

    public boolean isClosed() {
        return closed;
    }

    public SocketAddress localAddress() {
        Channel channel = listener;
        return channel == null ? null : channel.localAddress();
    }

    /** Binds synchronously; on failure releases everything it created and throws NetworkException. */
    public void start(ChannelHandler childHandler) {
        checkThread();
        synchronized (this) {
            if (started || closed) throw new IllegalStateException(name + " already started or closed");
            started = true;
            try {
                if (ownBoss) boss = NetworkTransports.newEventLoopGroup(1);
                if (ownWorker) worker = NetworkTransports.newEventLoopGroup(0);
                ServerBootstrap bootstrap = new ServerBootstrap().group(boss, worker)
                        .channelFactory(factory != null ? factory : NetworkTransports.serverChannelFactory(boss))
                        .childHandler(childHandler);
                applyOptions(bootstrap);
                ChannelFuture bind = bootstrap.bind(address);
                listener = bind.channel();
                bind.sync();
            } catch (Throwable cause) {
                closed = true;
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                try { cleanup(); } catch (Throwable cleanup) { cause.addSuppressed(cleanup); }
                throw new NetworkException(name + " bind failed: " + address, cause);
            }
        }
    }

    /** Idempotent and synchronous; throws NetworkException after cleanup when the caller was interrupted. */
    public void close() {
        if (closed) return;
        checkThread();
        synchronized (this) {
            if (closed) return;
            closed = true;
            cleanup();
            if (Thread.currentThread().isInterrupted())
                throw new NetworkException(name + " close interrupted", new InterruptedException(name + " close"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyOptions(ServerBootstrap bootstrap) {
        options.forEach((key, value) -> bootstrap.option((ChannelOption) key, value));
        childOptions.forEach((key, value) -> bootstrap.childOption((ChannelOption) key, value));
    }

    private void checkThread() {
        for (EventExecutorGroup group : new EventExecutorGroup[] {boss, worker, extraExecutor}) {
            if (group == null) continue;
            for (var executor : group) {
                if (executor.inEventLoop())
                    throw new IllegalStateException("Blocking " + name + " lifecycle operation on its own thread");
            }
        }
    }

    private void cleanup() {
        try {
            if (listener != null) await(listener.close(), name + " listener close failed");
        } finally {
            try {
                if (ownBoss && boss != null) shutdown(boss);
            } finally {
                if (ownWorker && worker != null && worker != boss) shutdown(worker);
            }
        }
    }

    /** Graceful shutdown with no quiet period; waits up to five seconds. */
    public static void shutdown(EventExecutorGroup group) {
        await(group.shutdownGracefully(0, 5, TimeUnit.SECONDS), "EventLoop shutdown failed");
    }

    /** Waits uninterruptibly so cleanup always completes; the outer operation reports interruption. */
    public static void await(Future<?> future, String message) {
        future.awaitUninterruptibly();
        if (!future.isSuccess()) throw new NetworkException(message, future.cause());
    }
}
