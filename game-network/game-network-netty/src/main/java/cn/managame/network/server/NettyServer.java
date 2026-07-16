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
        if (serverChannel != null && serverChannel.isActive()) {
            return;
        }
        try {
            serverChannel = bootstrap.bind(bindAddress).syncUninterruptibly().channel();
            channels.add(serverChannel);
        } catch (RuntimeException | Error failure) {
            shutdownEventLoops();
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        Channel current = serverChannel;
        serverChannel = null;
        try {
            channels.close().syncUninterruptibly();
            if (current != null) {
                current.close().syncUninterruptibly();
            }
        } finally {
            shutdownEventLoops();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        Channel current = serverChannel;
        return current != null && current.isActive();
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
}
