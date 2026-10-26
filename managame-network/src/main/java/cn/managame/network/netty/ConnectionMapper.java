package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import io.netty.channel.Channel;

/**
 * Decides how a Netty Channel maps to a Connection: which object represents the channel, and how that object
 * is found again. The server never keeps a connection of its own; it asks the mapper:
 * <ul>
 * <li>{@link #create} once per channel when establishment completes; its result goes to onActive;</li>
 * <li>{@link #get} for every later callback, so the mapping may change during the connection's life, for
 * example to resume an existing session on a reconnecting client's channel. A channel get returns null for
 * receives no callbacks, and an exception on it closes it;</li>
 * <li>{@link #remove} right after onInActive. After it, get should return null, so nothing arrives after
 * onInActive.</li>
 * </ul>
 * The server calls all three on the channel's EventLoop. The default, {@link #attribute()}, creates a
 * {@link NettyConnection} and keeps it in {@link NetworkAttributes#CONNECTION}.
 */
public interface ConnectionMapper {
    /**
     * The connection for a newly established channel: a new object or an existing one. Must not return null;
     * a failure or null closes the channel without onActive.
     */
    Connection create(Channel channel);

    /** The connection for {@code channel}; null before create, after remove, or to skip callbacks meanwhile. */
    Connection get(Channel channel);

    /** Undoes what create set up, right after onInActive. */
    void remove(Channel channel);

    /**
     * A new NettyConnection per channel, kept in the channel's {@link NetworkAttributes#CONNECTION} attribute
     * until remove.
     */
    static ConnectionMapper attribute() { return AttributeConnectionMapper.INSTANCE; }
}
