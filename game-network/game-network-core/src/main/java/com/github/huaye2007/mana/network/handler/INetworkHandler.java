package com.github.huaye2007.mana.network.handler;

import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.session.DefaultSession;
import com.github.huaye2007.mana.network.session.ISession;

public interface INetworkHandler {

    void onConnect(ISession session);

    void onMessage(ISession session, Object packet);

    void onDisconnect(ISession session);

    void onException(ISession session, Throwable cause);

    default boolean onIdle(ISession session) { return false; }

    ISession createSession(IConnection connection);
}
