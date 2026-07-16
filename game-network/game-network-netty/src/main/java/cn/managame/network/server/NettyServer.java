package cn.managame.network.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;

import java.net.SocketAddress;
import java.util.Objects;

/** A protocol-neutral lifecycle wrapper around a configured Netty {@link ServerBootstrap}. */
public final class NettyServer implements INetworkServer, AutoCloseable {

    private final ServerBootstrap bootstrap;
    private final SocketAddress bindAddress;
    private final ChannelGroup channels;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final boolean shutdownEventLoops;

    private volatile Channel serverChannel;
    private volatile State state = State.NEW;

    NettyServer(ServerBootstrap bootstrap, SocketAddress bindAddress, ChannelGroup channels,
                EventLoopGroup bossGroup, EventLoopGroup workerGroup, boolean shutdownEventLoops) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.bossGroup = Objects.requireNonNull(bossGroup, "bossGroup");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.shutdownEventLoops = shutdownEventLoops;
    }

    public static NettyServerBuilder tcp(cn.managame.network.handler.IConnectionHandler handler) {
        return NettyServerBuilder.tcp(handler);
    }

    public static NettyServerBuilder webSocket(
            io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig protocol,
            cn.managame.network.handler.IConnectionHandler handler) {
        return NettyServerBuilder.webSocket(protocol, handler);
    }

    public static NettyServerBuilder custom(io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel> initializer) {
        return NettyServerBuilder.custom(initializer);
    }

    @Override
    public synchronized void start() {
        if (state == State.RUNNING) {
            return;
        }
        if (state == State.STOPPED) {
            throw new IllegalStateException("server cannot be restarted after it has stopped");
        }
        try {
            serverChannel = bootstrap.bind(bindAddress).syncUninterruptibly().channel();
            channels.add(serverChannel);
            state = State.RUNNING;
        } catch (RuntimeException | Error failure) {
            state = State.STOPPED;
            shutdownEventLoops();
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        if (state == State.STOPPED) {
            return;
        }
        state = State.STOPPED;
        Channel current = serverChannel;
        serverChannel = null;
        try {
            if (current != null) {
                current.close().syncUninterruptibly();
            }
        } finally {
            try {
                channels.close().syncUninterruptibly();
            } finally {
                shutdownEventLoops();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return state == State.RUNNING && serverChannel != null && serverChannel.isActive();
    }

    public SocketAddress localAddress() {
        Channel current = serverChannel;
        return current == null ? null : current.localAddress();
    }

    private void shutdownEventLoops() {
        if (!shutdownEventLoops) {
            return;
        }
        workerGroup.shutdownGracefully().syncUninterruptibly();
        if (bossGroup != workerGroup) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    private enum State { NEW, RUNNING, STOPPED }
}
