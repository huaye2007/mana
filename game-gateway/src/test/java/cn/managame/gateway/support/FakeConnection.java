package cn.managame.gateway.support;

import cn.managame.network.connection.ConnectionType;
import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.IWriteCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 无网络的 {@link IConnection} 测试替身：记录写出的消息，供断言。
 */
public class FakeConnection implements IConnection {

    private final long connectionId;
    private final String remoteAddress;
    private final List<Object> written = new ArrayList<>();
    private boolean closed;

    public FakeConnection(long connectionId, String remoteAddress) {
        this.connectionId = connectionId;
        this.remoteAddress = remoteAddress;
    }

    public List<Object> written() {
        return written;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public long getConnectionId() {
        return connectionId;
    }

    @Override
    public ConnectionType getType() {
        return ConnectionType.TCP;
    }

    @Override
    public String getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public void writeMsg(Object packet) {
        written.add(packet);
    }

    @Override
    public void writeMsg(Object packet, IWriteCallback writeCallback) {
        written.add(packet);
        if (writeCallback != null) {
            writeCallback.onSuccess();
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isActive() {
        return !closed;
    }

    @Override
    public boolean isWritable() {
        return true;
    }
}
