package cn.managame.network;

/**
 * User callbacks invoked directly by the native pipeline on the connection's EventLoop. Different
 * connections may run concurrently. Do not block the EventLoop.
 */
public interface NetworkHandler {
    /** Initialize the connected session. Throwing fails establishment and closes the connection. */
    default void onConnected(Connection connection) throws Exception {}

    /** The message is borrowed until this callback returns. Retain or copy to keep it. */
    void onMessage(Connection connection, Object message) throws Exception;

    /** Maps the native channel-inactive event. Attributes follow native channel lifetime. */
    default void onDisconnected(Connection connection) throws Exception {}

    default void onException(Connection connection, Throwable cause) throws Exception {
        connection.close();
    }

    default void onIdle(Connection connection, IdleType type) throws Exception {}
}
