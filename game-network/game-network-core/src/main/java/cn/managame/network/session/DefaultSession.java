package cn.managame.network.session;

import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.IWriteCallback;

public class DefaultSession implements ISession{

    private IConnection connection;

    public DefaultSession(IConnection connection){

        this.connection = connection;
    }

    @Override
    public IConnection getConnection() {
        return connection;
    }

    @Override
    public void setConnection(IConnection connection) {
        this.connection = connection;
    }

    @Override
    public void writeMsg(Object packet) {
        connection.writeMsg(packet);
    }

    @Override
    public void writeMsg(Object packet, IWriteCallback writeCallback) {
        connection.writeMsg(packet,writeCallback);
    }

    @Override
    public void close() {
        connection.close();
    }
}
