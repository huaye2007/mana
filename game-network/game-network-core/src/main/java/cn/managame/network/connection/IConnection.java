package cn.managame.network.connection;

public interface IConnection {

    /** Stable transport identifier. Implementations must not reuse it while the process is running. */
    String getConnectionId();

    ConnectionType getType();

    String getRemoteAddress();

    void writeMsg(Object packet);

    void writeMsg(Object packet, IWriteCallback writeCallback);

    void close();

    boolean isActive();

    boolean isWritable();
}
