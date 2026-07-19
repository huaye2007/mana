package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.support.FakeConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayGuardTest {
    private static GatewaySession session(long id, String ip) {
        return new GatewaySession(id, new FakeConnection(id, ip), ip);
    }

    private static GatewayPacket packet(int command) {
        return GatewayPacket.of(command, 1, 0, GatewayPacketConstant.EMPTY_BODY);
    }

    private static GatewayGuard guard(int maxConnections, double sessionRate, double sessionBurst) {
        return new GatewayGuard(1000, maxConnections, sessionRate, sessionBurst, 100, 100);
    }

    @Test void gatesUnauthenticatedCommands() {
        GatewaySession session = session(1, "1.1.1.1");
        GatewayGuard guard = guard(10, 10, 10);
        assertTrue(guard.onConnect(session));
        assertEquals(GatewayErrorCode.OK, guard.onPacket(session, packet(1000)));
        assertEquals(GatewayErrorCode.NOT_LOGGED_IN, guard.onPacket(session, packet(2000)));
        session.setAuthenticated(true);
        assertEquals(GatewayErrorCode.OK, guard.onPacket(session, packet(2000)));
    }

    @Test void rateLimitsSessionsIndependently() {
        GatewayGuard guard = guard(10, 1, 1);
        GatewaySession a = session(1, "1.1.1.1");
        GatewaySession b = session(2, "1.1.1.1");
        assertTrue(guard.onConnect(a));
        assertTrue(guard.onConnect(b));
        assertEquals(GatewayErrorCode.OK, guard.onPacket(a, packet(1000)));
        assertEquals(GatewayErrorCode.RATE_LIMITED, guard.onPacket(a, packet(1000)));
        assertEquals(GatewayErrorCode.OK, guard.onPacket(b, packet(1000)));
    }

    @Test void connectionLimitAccountingIsIdempotent() {
        GatewayGuard guard = guard(1, 10, 10);
        GatewaySession accepted = session(1, "9.9.9.9");
        GatewaySession rejected = session(2, "9.9.9.9");
        assertTrue(guard.onConnect(accepted));
        assertFalse(guard.onConnect(rejected));
        guard.onDisconnect(rejected);
        assertFalse(guard.onConnect(session(3, "9.9.9.9")),
                "rejected disconnect must not free another session's slot");
        guard.onDisconnect(accepted);
        assertTrue(guard.onConnect(session(4, "9.9.9.9")));
    }
}
