package cn.managame.gateway.session;

import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.support.FakeConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewaySessionManagerTest {
    @Test void duplicateRoleBindingKicksPreviousSessionWithoutRemovingNewIndex() {
        GatewaySessionManager manager = new GatewaySessionManager();
        FakeConnection firstConnection = new FakeConnection(1, "1.1.1.1");
        FakeConnection secondConnection = new FakeConnection(2, "2.2.2.2");
        GatewaySession first = new GatewaySession(firstConnection, "1.1.1.1");
        GatewaySession second = new GatewaySession(secondConnection, "2.2.2.2");
        manager.add(first);
        manager.add(second);
        manager.bindRole(first, 99);
        manager.bindRole(second, 99);
        assertSame(second, manager.getByRoleId(99));
        assertFalse(firstConnection.isActive());
        assertEquals(1, firstConnection.writes().size());
        assertInstanceOf(GatewayPacket.class, firstConnection.writes().getFirst());
        manager.remove(first);
        assertSame(second, manager.getByRoleId(99));
    }
}
