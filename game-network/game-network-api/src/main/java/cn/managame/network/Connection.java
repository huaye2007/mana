package cn.managame.network;

import java.net.SocketAddress;

public interface Connection {
    /** Opaque identifier supplied by the underlying transport. */
    String id();

    /** Protocol selected for this connection; does not indicate handshake completion. */
    ConnectionType type();

    boolean isActive();

    boolean isWritable();

    /**
     * Delegates to the transport's write-and-flush path. True transfers one owned reference; false
     * leaves ownership with the caller. This is not a delivery acknowledgement. Concurrent callers
     * follow native transport ordering. Write completion and failure belong to the native write
     * result; this boolean is not that result.
     */
    boolean write(Object message);

    /** Idempotent non-blocking close request. */
    void close();

    SocketAddress remoteAddress();

    <T> T get(AttributeKey<T> key);

    <T> void set(AttributeKey<T> key, T value);

    <T> T remove(AttributeKey<T> key);

    <T> boolean compareAndSet(AttributeKey<T> key, T expected, T update);
}
