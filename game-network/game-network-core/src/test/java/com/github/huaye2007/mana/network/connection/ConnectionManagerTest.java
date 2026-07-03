package com.github.huaye2007.mana.network.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConnectionManagerTest {

    @Test
    void addGetRemoveAndCloseAll() {
        ConnectionManager manager = new ConnectionManager();
        TestConnection connection1 = new TestConnection(1);
        TestConnection connection2 = new TestConnection(2);
        Object channel1 = new Object();
        Object channel2 = new Object();

        manager.addConnection(connection1, channel1);
        manager.addConnection(connection2, channel2);

        assertEquals(2, manager.size());
        assertSame(connection1, manager.getConnection(channel1));
        assertSame(connection2, manager.getConnection(2));

        assertSame(connection1, manager.removeConnectionByChannel(channel1));
        assertFalse(manager.containsConnection(1));
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
