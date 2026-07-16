package cn.managame.dev.server;

import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.IWriteCallback;

import java.util.Objects;

public class PlayerSession {
    private final Object sessionId;
    private final IConnection connection;
    private long roleId;

    public PlayerSession(IConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionId = connection.getConnectionId();
    }

    public Object getSessionId() { return sessionId; }
    public IConnection getConnection() { return connection; }
    public void writeMsg(Object message) { connection.writeMsg(message); }
    public void writeMsg(Object message, IWriteCallback callback) { connection.writeMsg(message, callback); }
    public void close() { connection.close(); }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(long roleId) {
        this.roleId = roleId;
    }
}
