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

    /**
     * Optional write completion notification. Null means local write success, otherwise the cause.
     * True transfers ownership even if completion reports failure; false retains caller ownership
     * and does not invoke completion. Notification may run before return or on the transport thread;
     * it must return promptly. It does not acknowledge peer receipt or business processing.
     * Implementations without completion support throw before taking ownership.
     */
    default boolean write(Object message, java.util.function.Consumer<? super Throwable> completion) {
        throw new UnsupportedOperationException("Write completion is not supported by this transport");
    }

    /** Idempotent non-blocking close request. */
    void close();

    SocketAddress remoteAddress();

    <T> T get(AttributeKey<T> key);

    <T> void set(AttributeKey<T> key, T value);

    <T> T remove(AttributeKey<T> key);

    <T> boolean compareAndSet(AttributeKey<T> key, T expected, T update);
}
