package cn.managame.network.handler;

import cn.managame.network.connection.IConnection;

public interface IConnectionHandler {

    void onConnect(IConnection connection);


    void onMessage(IConnection connection, Object packet);

    /**
     * Lets a transport reject and release a decoded message before handing it to the application.
     * Implementations that maintain a separate session lifecycle should return {@code false}
     * until that session is fully installed.
     */
    default boolean isReadyForMessages(IConnection connection) {
        return true;
    }


    void onDisconnect(IConnection connection);


    void onException(IConnection connection, Throwable cause);


    default void onIdle(IConnection connection) {}
}
