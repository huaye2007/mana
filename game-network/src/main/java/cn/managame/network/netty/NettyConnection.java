package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.net.SocketAddress;
import java.util.Objects;

final class NettyConnection implements Connection {
    private final Channel channel;
    private final NetworkCounters counters;
    NettyConnection(Channel channel, NetworkCounters counters) { this.channel = channel; this.counters = counters; }
    public boolean isActive() { return channel.isActive(); }
    public boolean isWritable() { return channel.isWritable(); }
    public WriteStatus write(Object message) {
        Objects.requireNonNull(message, "message");
        if (!channel.isActive()) { counters.writeInactive(); return WriteStatus.INACTIVE; }
        if (!channel.isWritable()) { counters.writeNotWritable(); return WriteStatus.NOT_WRITABLE; }
        // Reusable void promise forwards outbound failures into exceptionCaught.
        channel.writeAndFlush(message, channel.voidPromise());
        counters.writeAccepted();
        return WriteStatus.ACCEPTED;
    }
    public void close() { channel.close(); }
    public SocketAddress localAddress() { return channel.localAddress(); }
    public SocketAddress remoteAddress() { return channel.remoteAddress(); }
    public <T> T get(AttributeKey<T> key) { return channel.attr(key).get(); }
    public <T> void set(AttributeKey<T> key, T value) { channel.attr(key).set(value); }
    public <T> T remove(AttributeKey<T> key) { return channel.attr(key).getAndSet(null); }
}