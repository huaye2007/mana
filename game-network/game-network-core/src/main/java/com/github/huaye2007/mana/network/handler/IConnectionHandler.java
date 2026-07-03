package com.github.huaye2007.mana.network.handler;

import com.github.huaye2007.mana.network.connection.IConnection;

public interface IConnectionHandler {

    void onConnect(IConnection connection);


    void onMessage(IConnection connection, Object packet);


    void onDisconnect(IConnection connection);


    void onException(IConnection connection, Throwable cause);


    default void onIdle(IConnection connection) {}
}
