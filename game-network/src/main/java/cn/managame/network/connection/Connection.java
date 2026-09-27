package cn.managame.network.connection;

import io.netty.util.AttributeKey;
import java.net.SocketAddress;

/** A transport-established connection. Accepted writes transfer ownership to Netty. */
public interface Connection {
    boolean isActive();
    boolean isWritable();
    WriteStatus write(Object message);
    /** Nonblocking and idempotent; disconnection is reported after channel inactivity. */
    void close();
    SocketAddress localAddress();
    SocketAddress remoteAddress();
    <T> T get(AttributeKey<T> key);
    <T> void set(AttributeKey<T> key, T value);
    <T> T remove(AttributeKey<T> key);
}