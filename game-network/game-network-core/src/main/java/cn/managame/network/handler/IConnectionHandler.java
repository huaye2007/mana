package cn.managame.network.handler;

import cn.managame.network.connection.IConnection;

/**
 * Receives connection events serially on that connection's Netty event-loop thread.
 * Implementations must offload blocking work. A reference-counted {@code packet} passed to
 * {@link #onMessage(IConnection, Object)} becomes the implementation's responsibility to release.
 */
public interface IConnectionHandler {

    void onConnect(IConnection connection);


    void onMessage(IConnection connection, Object packet);


    void onDisconnect(IConnection connection);


    void onException(IConnection connection, Throwable cause);


    default void onIdle(IConnection connection) { }
}
