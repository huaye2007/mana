package cn.managame.network.connection;

/** Callbacks run on the channel EventLoop. Shared handlers must support concurrent connections. */
public interface ConnectionHandler {
    void onConnected(Connection connection);
    /** Reference-counted messages are borrowed and released after this callback. */
    void onMessage(Connection connection, Object message);
    void onDisconnected(Connection connection);
    /** Events retain their producer's ownership convention; no automatic release. */
    default void onEvent(Connection connection, Object event) {}
    /**
     * Errors on a connection that is already established. Not named onFail like the one-shot failure
     * branches elsewhere, because it is not one: it can run many times for one connection, it is paired
     * with no success callback, and it reports that something went wrong rather than that an operation
     * did not succeed.
     */
    void onException(Connection connection, Throwable cause);
}