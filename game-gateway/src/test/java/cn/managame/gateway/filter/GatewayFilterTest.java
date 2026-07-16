package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.support.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GatewayFilterTest {
    private static GatewaySession session(long id, String ip) { return new GatewaySession(id, new FakeConnection(id, ip), ip); }
    private static GatewayPacket packet(int command) { return GatewayPacket.of(command, 1, 0, GatewayPacketConstant.EMPTY_BODY); }

    @Test void gatesUnauthenticatedCommands() {
        GatewaySession session = session(1, "1.1.1.1");
        AuthFilter filter = new AuthFilter(1000);
        assertEquals(GatewayErrorCode.OK, filter.onPacket(session, packet(1000)));
        assertEquals(GatewayErrorCode.NOT_LOGGED_IN, filter.onPacket(session, packet(2000)));
        session.setAuthenticated(true);
        assertEquals(GatewayErrorCode.OK, filter.onPacket(session, packet(2000)));
    }

    @Test void rateLimitsSessionsIndependently() {
        RateLimitFilter filter = new RateLimitFilter(1, 1);
        GatewaySession a = session(1, "1.1.1.1");
        GatewaySession b = session(2, "1.1.1.1");
        assertEquals(GatewayErrorCode.OK, filter.onPacket(a, packet(2)));
        assertEquals(GatewayErrorCode.RATE_LIMITED, filter.onPacket(a, packet(2)));
        assertEquals(GatewayErrorCode.OK, filter.onPacket(b, packet(2)));
    }

    @Test void connectionLimitAccountingIsIdempotent() {
        DdosFilter filter = new DdosFilter(1, 10, 10);
        GatewaySession accepted = session(1, "9.9.9.9");
        GatewaySession rejected = session(2, "9.9.9.9");
        assertTrue(filter.onConnect(accepted));
        assertFalse(filter.onConnect(rejected));
        filter.onDisconnect(rejected);
        assertFalse(filter.onConnect(session(3, "9.9.9.9")), "rejected disconnect must not free another session's slot");
        filter.onDisconnect(accepted);
        assertTrue(filter.onConnect(session(4, "9.9.9.9")));
    }

    @Test void rollsBackEarlierFiltersWhenConnectIsRejected() {
        AtomicInteger disconnects = new AtomicInteger();
        GatewayFilter first = new GatewayFilter() { @Override public void onDisconnect(GatewaySession s) { disconnects.incrementAndGet(); } };
        GatewayFilter reject = new GatewayFilter() { @Override public boolean onConnect(GatewaySession s) { return false; } };
        assertFalse(new FilterChain(List.of(first, reject)).onConnect(session(1, "x")));
        assertEquals(1, disconnects.get());
    }
}
