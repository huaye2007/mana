package cn.managame.network.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/**
 * Native transport selection. Epoll is used when its native library is on the class path and loads
 * (Linux with {@code netty-transport-native-epoll}); otherwise NIO. Channel factories always match the
 * group they run on, so a caller-supplied NIO group keeps NIO channels even where Epoll is available.
 */
public final class NetworkTransports {
    private NetworkTransports() {}

    /** New caller-owned group of the preferred transport; zero threads uses Netty's default count. */
    public static EventLoopGroup newEventLoopGroup(int threads, ThreadFactory threadFactory) {
        if (threads < 0) throw new IllegalArgumentException("threads must not be negative");
        return Epoll.isAvailable()
                ? new EpollEventLoopGroup(threads, threadFactory)
                : new NioEventLoopGroup(threads, threadFactory);
    }

    /** New caller-owned group of the preferred transport with Netty's default thread factory. */
    public static EventLoopGroup newEventLoopGroup(int threads) {
        return newEventLoopGroup(threads, (ThreadFactory) null);
    }

    /** Client channel type that runs on {@code group}. */
    public static ChannelFactory<? extends Channel> socketChannelFactory(EventLoopGroup group) {
        return Objects.requireNonNull(group, "group") instanceof EpollEventLoopGroup
                ? EpollSocketChannel::new
                : NioSocketChannel::new;
    }

    /** Listener channel type that runs on {@code group}. */
    public static ChannelFactory<? extends ServerChannel> serverChannelFactory(EventLoopGroup group) {
        return Objects.requireNonNull(group, "group") instanceof EpollEventLoopGroup
                ? EpollServerSocketChannel::new
                : NioServerSocketChannel::new;
    }
}
