package cn.managame.network.netty;

import cn.managame.network.AttributeKey;
import cn.managame.network.Connection;
import cn.managame.network.ConnectionType;

import io.netty.channel.Channel;
import io.netty.util.Attribute;

import java.net.SocketAddress;
import java.util.Objects;

/** Thin adapter over a Netty Channel; owns no lifecycle or message queue. */
public final class NettyConnection implements Connection {
    final Channel channel;
    private final ConnectionType type;

    public NettyConnection(Channel channel, ConnectionType type) {
        this.channel = Objects.requireNonNull(channel);
        this.type = Objects.requireNonNull(type);
    }

    @Override
    public ConnectionType type() {
        return type;
    }

    @Override
    public String id() {
        return channel.id().asLongText();
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public boolean isWritable() {
        return channel.isWritable();
    }

    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public boolean write(Object message) {
        if (!channel.isActive()) return false;
        channel.writeAndFlush(message);
        return true;
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public <T> T get(AttributeKey<T> key) {
        return key.cast(attribute(key).get());
    }

    @Override
    public <T> void set(AttributeKey<T> key, T value) {
        attribute(key).set(key.cast(value));
    }

    @Override
    public <T> T remove(AttributeKey<T> key) {
        return key.cast(attribute(key).getAndSet(null));
    }

    @Override
    public <T> boolean compareAndSet(AttributeKey<T> key, T expected, T update) {
        return attribute(key).compareAndSet(expected, key.cast(update));
    }

    private <T> Attribute<T> attribute(AttributeKey<T> key) {
        return channel.attr(io.netty.util.AttributeKey.valueOf(key.id()));
    }
}
