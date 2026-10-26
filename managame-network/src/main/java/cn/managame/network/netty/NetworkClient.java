package cn.managame.network.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.BootstrapConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A connection to one remote address that stays up: the client connects at once, and whenever connecting fails
 * or the connection closes it tries again, waiting twice as long after each failure up to a maximum, and from
 * the minimum again after a success. What each connection does is its handler, normally a
 * {@link ConnectionInitializer}, whose ConnectionHandler sees every connection come and go:
 *
 * <pre>{@code
 * NetworkClient client = NetworkClient.connect(new InetSocketAddress("10.0.0.2", 7001), initializer);
 * }</pre>
 *
 * The client keeps its current channel only to close it. {@link #close} stops reconnecting and closes it.
 */
public final class NetworkClient implements AutoCloseable {
    public static final Duration DEFAULT_MIN_RETRY = Duration.ofMillis(100);
    public static final Duration DEFAULT_MAX_RETRY = Duration.ofSeconds(10);
    private final Bootstrap bootstrap;
    private final SocketAddress remote;
    private final List<EventLoopGroup> ownedGroups;
    private final EventLoop retryLoop;
    private final long minRetryNanos, maxRetryNanos;
    private volatile long retryNanos;
    private volatile Channel channel;
    private volatile boolean closed;

    private NetworkClient(Bootstrap bootstrap, SocketAddress remote, List<EventLoopGroup> ownedGroups,
                          Duration minRetry, Duration maxRetry) {
        this.bootstrap = bootstrap;
        this.remote = remote;
        this.ownedGroups = ownedGroups;
        retryLoop = bootstrap.config().group().next();
        minRetryNanos = minRetry.toNanos();
        maxRetryNanos = maxRetry.toNanos();
        retryNanos = minRetryNanos;
    }

    /** Connects with defaults for everything; see {@link #connect(Bootstrap, SocketAddress, Duration, Duration)}. */
    public static NetworkClient connect(SocketAddress remote, ChannelHandler handler) {
        return connect(new Bootstrap().handler(Objects.requireNonNull(handler, "handler")), remote);
    }

    public static NetworkClient connect(Bootstrap bootstrap, SocketAddress remote) {
        return connect(bootstrap, remote, DEFAULT_MIN_RETRY, DEFAULT_MAX_RETRY);
    }

    /**
     * Connects with the application's bootstrap, which must set the handler. What it leaves unset is filled in
     * on a copy, as {@link NetworkServer} does: an EventLoop of the client's own, shut down on close; the channel
     * type matching the EventLoops; TCP_NODELAY.
     */
    public static NetworkClient connect(Bootstrap bootstrap, SocketAddress remote, Duration minRetry, Duration maxRetry) {
        Objects.requireNonNull(remote, "remote");
        if (minRetry.toMillis() < 1 || maxRetry.compareTo(minRetry) < 0)
            throw new IllegalArgumentException("Retry delays must be at least one millisecond, the maximum no less than the minimum");
        BootstrapConfig config = bootstrap.config();
        Bootstrap completed = bootstrap.clone();
        List<EventLoopGroup> owned = List.of();
        if (config.group() == null) {
            owned = List.of(NetworkTransports.newEventLoopGroup(1, "network-client"));
            completed.group(owned.getFirst());
        }
        if (config.channelFactory() == null)
            completed.channelFactory(NetworkTransports.socketChannelFactory(completed.config().group()));
        if (!config.options().containsKey(ChannelOption.TCP_NODELAY)) completed.option(ChannelOption.TCP_NODELAY, true);
        NetworkClient client = new NetworkClient(completed, remote, owned, minRetry, maxRetry);
        client.connectNow();
        return client;
    }

    private void connectNow() {
        if (closed) return;
        bootstrap.connect(remote).addListener((ChannelFuture connected) -> {
            if (!connected.isSuccess()) {
                retryLater();
                return;
            }
            Channel current = connected.channel();
            if (closed) {
                current.close();
                return;
            }
            channel = current;
            retryNanos = minRetryNanos;
            current.closeFuture().addListener(gone -> retryLater());
        });
    }

    private void retryLater() {
        if (closed) return;
        long delay = retryNanos;
        retryNanos = Math.min(delay * 2, maxRetryNanos);
        retryLoop.schedule(this::connectNow, delay, TimeUnit.NANOSECONDS);
    }

    public boolean isConnected() {
        Channel current = channel;
        return current != null && current.isActive();
    }

    public SocketAddress remoteAddress() { return remote; }

    /** Stops reconnecting and closes the connection; shuts down the client's own EventLoop. */
    @Override public void close() {
        closed = true;
        for (EventLoopGroup group : ownedGroups)
            for (EventExecutor executor : group)
                if (executor.inEventLoop()) throw new IllegalStateException("Cannot close a client on its own EventLoop");
        Channel current = channel;
        try {
            if (current != null) current.close().awaitUninterruptibly();
        } finally {
            for (EventLoopGroup group : ownedGroups) group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }

    @Override public String toString() { return "NetworkClient[" + remote + "]"; }
}
