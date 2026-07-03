package com.github.huaye2007.mana.network.session;

import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.connection.IWriteCallback;

public interface ISession {

    IConnection getConnection();

    void setConnection(IConnection connection);

    void writeMsg(Object packet);

    void writeMsg(Object packet, IWriteCallback writeCallback);

    void close();

}
