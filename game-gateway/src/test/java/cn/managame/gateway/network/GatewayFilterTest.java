package cn.managame.gateway.network;

import cn.managame.gateway.bootstrap.Gateway;
import cn.managame.gateway.bootstrap.GatewayConfig;
import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.filter.GatewayFilter;
import cn.managame.gateway.filter.GatewayFilters;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.support.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayFilterTest {
    private static GatewaySession session(long id, String ip) {
        return new GatewaySession(id, new FakeConnection(id, ip), ip);
    }

    private static GatewayPacket packet(int command) {
        return GatewayPacket.of(command, 1, 0, GatewayPacketConstant.EMPTY_BODY);
    }

    private static GatewayFilter[] filters(int maxConnections, double sessionRate, double sessionBurst) {
        return new GatewayFilter[] {
                GatewayFilters.ipProtection(maxConnections, 100, 100),
                GatewayFilters.sessionRateLimit(sessionRate, sessionBurst),
                GatewayFilters.loginRequired(1000)
        };
    }

    @Test void gatesUnauthenticatedCommands() {
        GatewaySession session = session(1, "1.1.1.1");
        GatewayFilter[] filters = filters(10, 10, 10);
        assertTrue(GatewayNetworkHandler.accept(filters, session));
        assertEquals(GatewayErrorCode.OK, GatewayNetworkHandler.filter(filters, session, packet(1000)));
        assertEquals(GatewayErrorCode.NOT_LOGGED_IN,
                GatewayNetworkHandler.filter(filters, session, packet(2000)));
        session.setAuthenticated(true);
        assertEquals(GatewayErrorCode.OK, GatewayNetworkHandler.filter(filters, session, packet(2000)));
    }

    @Test void rateLimitsSessionsIndependently() {
        GatewayFilter[] filters = filters(10, 1, 1);
        GatewaySession a = session(1, "1.1.1.1");
        GatewaySession b = session(2, "1.1.1.1");
        assertTrue(GatewayNetworkHandler.accept(filters, a));
        assertTrue(GatewayNetworkHandler.accept(filters, b));
        assertEquals(GatewayErrorCode.OK, GatewayNetworkHandler.filter(filters, a, packet(1000)));
        assertEquals(GatewayErrorCode.RATE_LIMITED, GatewayNetworkHandler.filter(filters, a, packet(1000)));
        assertEquals(GatewayErrorCode.OK, GatewayNetworkHandler.filter(filters, b, packet(1000)));
    }

    @Test void connectionLimitAccountingIsIdempotent() {
        GatewayFilter[] filters = filters(1, 10, 10);
        GatewaySession accepted = session(1, "9.9.9.9");
        GatewaySession rejected = session(2, "9.9.9.9");
        assertTrue(GatewayNetworkHandler.accept(filters, accepted));
        assertFalse(GatewayNetworkHandler.accept(filters, rejected));
        GatewayNetworkHandler.disconnect(filters, rejected);
        assertFalse(GatewayNetworkHandler.accept(filters, session(3, "9.9.9.9")),
                "rejected disconnect must not free another session's slot");
        GatewayNetworkHandler.disconnect(filters, accepted);
        assertTrue(GatewayNetworkHandler.accept(filters, session(4, "9.9.9.9")));
    }

    @Test void shortCircuitsAndRollsBackAcceptedFilters() {
        List<String> calls = new ArrayList<>();
        GatewayFilter accepted = new GatewayFilter() {
            @Override public boolean onConnect(GatewaySession session) {
                calls.add("connect");
                return true;
            }
            @Override public void onDisconnect(GatewaySession session) { calls.add("rollback"); }
        };
        GatewayFilter rejected = new GatewayFilter() {
            @Override public boolean onConnect(GatewaySession session) { return false; }
        };

        assertFalse(GatewayNetworkHandler.accept(
                new GatewayFilter[] {accepted, rejected}, session(1, "1.1.1.1")));
        assertEquals(List.of("connect", "rollback"), calls);
    }

    @Test void customRateLimitsAreGatewayFilters() {
        AtomicInteger packets = new AtomicInteger();
        GatewayFilter rateLimit = new GatewayFilter() {
            @Override
            public int onPacket(GatewaySession session, GatewayPacket packet) {
                return packets.incrementAndGet() < 2
                        ? GatewayErrorCode.OK : GatewayErrorCode.RATE_LIMITED;
            }
        };
        GatewayFilter[] filters = {rateLimit};
        GatewaySession session = session(1, "1.1.1.1");

        assertTrue(GatewayNetworkHandler.accept(filters, session));
        assertEquals(GatewayErrorCode.OK, GatewayNetworkHandler.filter(filters, session, packet(1000)));
        assertEquals(GatewayErrorCode.RATE_LIMITED,
                GatewayNetworkHandler.filter(filters, session, packet(1000)));
        assertEquals(2, packets.get());
    }

    @Test void gatewayDefaultCompositionAppendsApplicationFilters() {
        GatewayFilter applicationFilter = new GatewayFilter() { };
        GatewayFilter[] filters = Gateway.defaultFilters(new GatewayConfig(), applicationFilter);
        assertEquals(4, filters.length);
        assertEquals(applicationFilter, filters[3]);
    }
}
