package cn.managame.network.session;

import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.IWriteCallback;

public interface ISession {

    IConnection getConnection();

    void setConnection(IConnection connection);

    void writeMsg(Object packet);

    void writeMsg(Object packet, IWriteCallback writeCallback);

    void close();

}
