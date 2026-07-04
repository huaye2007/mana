package cn.managame.network.session;

import cn.managame.network.connection.ConnectionType;
import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.IWriteCallback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SessionManagerTest {

    @Test
    void addGetRemoveAndCloseAll() {
        SessionManager manager = new SessionManager();
        TestConnection connection1 = new TestConnection(1);
        TestConnection connection2 = new TestConnection(2);
        ISession session1 = new DefaultSession(connection1);
        ISession session2 = new DefaultSession(connection2);

        manager.addSession(session1);
        manager.addSession(session2);

        assertEquals(2, manager.size());
        assertSame(session1, manager.getSession(connection1));

        assertSame(session1, manager.removeSession(connection1));
        assertNull(manager.getSession(connection1));
        assertEquals(1, manager.size());

        manager.closeAll();

        assertEquals(0, manager.size());
        assertEquals(1, connection2.closeCount);
    }

    private static class TestConnection implements IConnection {
        private final long connectionId;
        private int closeCount;

        private TestConnection(long connectionId) {
            this.connectionId = connectionId;
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
            return "127.0.0.1";
        }

        @Override
        public void writeMsg(Object packet) {
        }

        @Override
        public void writeMsg(Object packet, IWriteCallback writeCallback) {
        }

        @Override
        public void close() {
            closeCount++;
        }

        @Override
        public boolean isActive() {
            return closeCount == 0;
        }

        @Override
        public boolean isWritable() {
            return closeCount == 0;
        }
    }
}
