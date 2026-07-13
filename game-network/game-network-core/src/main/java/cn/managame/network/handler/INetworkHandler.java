package cn.managame.network.handler;

import cn.managame.network.connection.IConnection;
import cn.managame.network.session.DefaultSession;
import cn.managame.network.session.ISession;

public interface INetworkHandler {

    void onConnect(ISession session);

    void onMessage(ISession session, Object packet);

    void onDisconnect(ISession session);

    void onException(ISession session, Throwable cause);

    default boolean onIdle(ISession session) { return false; }

    default ISession createSession(IConnection connection) {
        return new DefaultSession(connection);
    }
}
