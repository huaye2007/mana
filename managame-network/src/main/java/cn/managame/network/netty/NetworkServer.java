package cn.managame.network.netty;

import cn.managame.network.error.NetworkException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A bound listener. Everything about the listening socket — EventLoops, channel type, socket options — is
 * Netty's {@link ServerBootstrap}; what each connection looks like is its child handler, normally a
 * {@link ConnectionInitializer}. A server exists only once bound, so there is no start state to manage.
 *
 * <pre>{@code
 * // Defaults for everything: the server creates its own EventLoops and shuts them down on close.
 * NetworkServer server = NetworkServer.start(9001, game);
 *
 * // Socket options through Netty's own API; EventLoops are still the server's.
 * NetworkServer server = NetworkServer.start(new ServerBootstrap()
 *         .option(ChannelOption.SO_BACKLOG, 1024)
 *         .childOption(ChannelOption.SO_SNDBUF, 64 * 1024)
 *         .childHandler(game), new InetSocketAddress(9001));
 *
 * // The application's EventLoops, for example shared by several listeners.
 * NetworkServer server = NetworkServer.start(new ServerBootstrap()
 *         .group(boss, worker)
 *         .childHandler(game), new InetSocketAddress(9001));
 * }</pre>
 *
 * The server keeps no record of accepted connections: they belong to the application, which keeps and closes
 * them. {@link #close} closes the listener and, when the server created its EventLoops, shuts them down, which
 * also closes the connections still on them, so their onInActive calls have run when it returns. On the
 * application's EventLoops connections stay open.
 */
public final class NetworkServer implements AutoCloseable {
    private final Channel listener;
    private final List<EventLoopGroup> ownedGroups;

    private NetworkServer(Channel listener, List<EventLoopGroup> ownedGroups) {
        this.listener = listener;
        this.ownedGroups = ownedGroups;
    }

    /** Listens on all interfaces with defaults for everything; see {@link #start(ServerBootstrap, SocketAddress)}. */
    public static NetworkServer start(int port, ChannelHandler childHandler) {
        return start(new InetSocketAddress(port), childHandler);
    }

    /** Listens on {@code address} with defaults for everything; see {@link #start(ServerBootstrap, SocketAddress)}. */
    public static NetworkServer start(SocketAddress address, ChannelHandler childHandler) {
        return start(new ServerBootstrap().childHandler(Objects.requireNonNull(childHandler, "childHandler")), address);
    }

    /**
     * Binds the application's bootstrap, which must set the child handler. What it leaves unset is filled in
     * on a copy, so the bootstrap itself is not changed and can be reused:
     * <ul>
     * <li>no EventLoops: the server creates one boss thread and Netty's default number of workers, and shuts
     * them down on close. EventLoops the bootstrap sets stay the application's;</li>
     * <li>no channel type: the one matching the EventLoops, see {@link NetworkTransports#serverChannelFactory};</li>
     * <li>no TCP_NODELAY child option: true.</li>
     * </ul>
     * Port 0 binds an ephemeral port; see {@link #port}. A bind failure throws NetworkException.
     */
    public static NetworkServer start(ServerBootstrap bootstrap, SocketAddress address) {
        ServerBootstrapConfig config = bootstrap.config();
        ServerBootstrap completed = bootstrap.clone();
        List<EventLoopGroup> owned = List.of();
        if (config.group() == null) {
            owned = List.of(NetworkTransports.newEventLoopGroup(1, "network-boss"),
                    NetworkTransports.newEventLoopGroup(0, "network-worker"));
            completed.group(owned.get(0), owned.get(1));
        }
        try {
            if (config.channelFactory() == null)
                completed.channelFactory(NetworkTransports.serverChannelFactory(completed.config().group()));
            if (!config.childOptions().containsKey(ChannelOption.TCP_NODELAY))
                completed.childOption(ChannelOption.TCP_NODELAY, true);
            return new NetworkServer(bind(completed, address), owned);
        } catch (Throwable cause) {
            owned.forEach(NetworkServer::shutdown);
            throw cause;
        }
    }

    private static Channel bind(ServerBootstrap bootstrap, SocketAddress address) {
        ChannelFuture bind = bootstrap.bind(address).awaitUninterruptibly();
        if (!bind.isSuccess()) throw new NetworkException("Bind failed: " + address, bind.cause());
        return bind.channel();
    }

    public boolean isActive() { return listener.isActive(); }

    /** The bound address; reports the actual port when bound to port 0. */
    public InetSocketAddress localAddress() { return (InetSocketAddress) listener.localAddress(); }

    public int port() { return localAddress().getPort(); }

    /**
     * Idempotent; see the class description. Must not run on the server's EventLoops: Netty refuses to wait
     * on the listener's own, and the server cannot wait for EventLoops it owns to stop from one of them.
     */
    @Override public void close() {
        for (EventLoopGroup group : ownedGroups)
            for (EventExecutor executor : group)
                if (executor.inEventLoop()) throw new IllegalStateException("Cannot close a server on its own EventLoop");
        try {
            listener.close().awaitUninterruptibly();
        } finally {
            ownedGroups.forEach(NetworkServer::shutdown);
        }
    }

    private static void shutdown(EventLoopGroup group) {
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    @Override public String toString() {
        InetSocketAddress local = localAddress();
        return "NetworkServer[" + local.getHostString() + ":" + local.getPort() + "]";
    }
}
