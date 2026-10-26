package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * Connection backed by one Netty Channel, created by the default ConnectionMapper. A custom mapper may create
 * it, extend it with session state, or use a Connection implementation of its own.
 */
public class NettyConnection implements Connection {
    private final Channel channel;

    public NettyConnection(Channel channel) { this.channel = Objects.requireNonNull(channel, "channel"); }

    public final Channel channel() { return channel; }

    @Override public boolean isActive() { return channel.isActive(); }
    @Override public boolean isWritable() { return channel.isWritable(); }
    @Override public WriteStatus write(Object message) {
        Objects.requireNonNull(message, "message");
        if (!channel.isActive()) return WriteStatus.INACTIVE;
        if (!channel.isWritable()) return WriteStatus.NOT_WRITABLE;
        // The void promise reports write failures through exceptionCaught, without a listener per write.
        channel.writeAndFlush(message, channel.voidPromise());
        return WriteStatus.ACCEPTED;
    }
    @Override public void close() { channel.close(); }
    @Override public SocketAddress localAddress() { return channel.localAddress(); }
    @Override public SocketAddress remoteAddress() { return channel.remoteAddress(); }
    @Override public <T> T get(AttributeKey<T> key) { return channel.attr(key).get(); }
    @Override public <T> void set(AttributeKey<T> key, T value) { channel.attr(key).set(value); }
    @Override public <T> T remove(AttributeKey<T> key) { return channel.attr(key).getAndSet(null); }
    @Override public String toString() { return "Connection" + channel; }
}
