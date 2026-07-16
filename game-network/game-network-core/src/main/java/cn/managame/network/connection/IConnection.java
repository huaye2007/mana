package cn.managame.network.connection;

public interface IConnection {

    Object getConnectionId();

    ConnectionType getType();

    String getRemoteAddress();

    void writeMsg(Object packet);

    void writeMsg(Object packet, IWriteCallback writeCallback);

    void close();

    boolean isActive();

    boolean isWritable();
}
