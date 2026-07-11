package cn.managame.gateway.support;

import cn.managame.network.connection.ConnectionType;
import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.IWriteCallback;

import java.util.ArrayList;
import java.util.List;

public final class FakeConnection implements IConnection {
    private final long id;
    private final String remoteAddress;
    private final List<Object> writes = new ArrayList<>();
    private boolean active = true;

    public FakeConnection(long id, String remoteAddress) { this.id = id; this.remoteAddress = remoteAddress; }
    @Override public long getConnectionId() { return id; }
    @Override public ConnectionType getType() { return ConnectionType.TCP; }
    @Override public String getRemoteAddress() { return remoteAddress; }
    @Override public void writeMsg(Object packet) { writes.add(packet); }
    @Override public void writeMsg(Object packet, IWriteCallback callback) {
        writes.add(packet);
        if (active) callback.onSuccess(); else callback.onFailure(new IllegalStateException("closed"));
    }
    @Override public void close() { active = false; }
    @Override public boolean isActive() { return active; }
    @Override public boolean isWritable() { return active; }
    public List<Object> writes() { return List.copyOf(writes); }
}
