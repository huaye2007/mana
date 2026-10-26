package cn.managame.network.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * EventLoopGroup creation for servers and clients that share threads. Epoll is used when its native library loads
 * (Linux with {@code netty-transport-native-epoll}), NIO otherwise. Groups created here belong to the caller.
 */
public final class NetworkTransports {
    private NetworkTransports() {}

    /** Zero threads selects Netty's default, twice the available processors. */
    public static EventLoopGroup newEventLoopGroup(int threads, ThreadFactory threadFactory) {
        if (threads < 0) throw new IllegalArgumentException("threads must not be negative");
        Objects.requireNonNull(threadFactory, "threadFactory");
        return new MultiThreadIoEventLoopGroup(threads, threadFactory,
                Epoll.isAvailable() ? EpollIoHandler.newFactory() : NioIoHandler.newFactory());
    }

    /** Threads are named {@code <name>-<n>-<m>}. */
    public static EventLoopGroup newEventLoopGroup(int threads, String name) {
        return newEventLoopGroup(threads, new DefaultThreadFactory(name));
    }

    /** Listener channel type that runs on {@code group}, for a ServerBootstrap the application configures. */
    public static ChannelFactory<? extends ServerChannel> serverChannelFactory(EventLoopGroup group) {
        return isEpoll(group) ? EpollServerSocketChannel::new : NioServerSocketChannel::new;
    }

    /** Connecting channel type that runs on {@code group}, for a Bootstrap the application configures. */
    public static ChannelFactory<? extends Channel> socketChannelFactory(EventLoopGroup group) {
        return isEpoll(group) ? EpollSocketChannel::new : NioSocketChannel::new;
    }

    private static boolean isEpoll(EventLoopGroup group) {
        return group instanceof IoEventLoopGroup io && io.isIoType(EpollIoHandler.class);
    }
}
