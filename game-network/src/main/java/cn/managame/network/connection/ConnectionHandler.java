package cn.managame.network.connection;

/** Callbacks run on the channel EventLoop. Shared handlers must support concurrent connections. */
public interface ConnectionHandler {
    void onConnected(Connection connection);
    /** Reference-counted messages are borrowed and released after this callback. */
    void onMessage(Connection connection, Object message);
    void onDisconnected(Connection connection);
    /** Events retain their producer's ownership convention; no automatic release. */
    default void onEvent(Connection connection, Object event) {}
    void onException(Connection connection, Throwable cause);
}