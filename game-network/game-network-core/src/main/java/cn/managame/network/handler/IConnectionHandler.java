package cn.managame.network.handler;

import cn.managame.network.connection.IConnection;

public interface IConnectionHandler {

    void onConnect(IConnection connection);


    void onMessage(IConnection connection, Object packet);


    void onDisconnect(IConnection connection);


    void onException(IConnection connection, Throwable cause);


    default void onIdle(IConnection connection) { }
}
