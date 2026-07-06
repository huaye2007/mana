package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.support.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayFilterTest {

    private static final int LOGIN_COMMAND = 1000;

    private static GatewaySession session(long id, String ip) {
        return new GatewaySession(new FakeConnection(id, ip), ip);
    }

    private static GatewayPacket packet(int command) {
        return GatewayPacket.of(command, 1, 0, GatewayPacketConstant.EMPTY_BODY);
    }

    @Test
    void authFilterGatesUntilAuthenticated() {
        AuthFilter filter = new AuthFilter(LOGIN_COMMAND);
        GatewaySession session = session(1, "1.1.1.1");

        assertEquals(GatewayErrorCode.OK, filter.onPacket(session, packet(LOGIN_COMMAND)),
                "登录命令未认证也放行");
        assertEquals(GatewayErrorCode.NOT_LOGGED_IN, filter.onPacket(session, packet(2000)),
                "非登录命令未认证应拦下");

        session.setAuthenticated(true);
        assertEquals(GatewayErrorCode.OK, filter.onPacket(session, packet(2000)),
                "认证后放行其余命令");
    }

    @Test
    void rateLimitAllowsBurstThenBlocks() {
        RateLimitFilter filter = new RateLimitFilter(1, 3); // 每秒 1，突发 3
        GatewaySession session = session(1, "1.1.1.1");

        assertEquals(GatewayErrorCode.OK, filter.onPacket(session, packet(2000)));
        assertEquals(GatewayErrorCode.OK, filter.onPacket(session, packet(2000)));
        assertEquals(GatewayErrorCode.OK, filter.onPacket(session, packet(2000)));
        assertEquals(GatewayErrorCode.RATE_LIMITED, filter.onPacket(session, packet(2000)),
                "突发额度耗尽后应限流");
    }

    @Test
    void rateLimitPerSessionIsolated() {
        RateLimitFilter filter = new RateLimitFilter(1, 1);
        GatewaySession a = session(1, "1.1.1.1");
        GatewaySession b = session(2, "1.1.1.1");

        assertEquals(GatewayErrorCode.OK, filter.onPacket(a, packet(2000)));
        assertEquals(GatewayErrorCode.RATE_LIMITED, filter.onPacket(a, packet(2000)));
        assertEquals(GatewayErrorCode.OK, filter.onPacket(b, packet(2000)), "另一会话额度独立");
    }

    @Test
    void ddosCapsConnectionsPerIpAndReleasesOnDisconnect() {
        DdosFilter filter = new DdosFilter(2, 1000, 1000);
        GatewaySession s1 = session(1, "9.9.9.9");
        GatewaySession s2 = session(2, "9.9.9.9");
        GatewaySession s3 = session(3, "9.9.9.9");

        assertTrue(filter.onConnect(s1));
        assertTrue(filter.onConnect(s2));
        assertFalse(filter.onConnect(s3), "同 IP 超过并发上限应拒绝");

        filter.onDisconnect(s1);
        assertTrue(filter.onConnect(s3), "释放一个连接后同 IP 可再接入");
    }

    @Test
    void ddosPacketRateLimitPerIp() {
        DdosFilter filter = new DdosFilter(10, 1, 2); // 聚合每秒 1、突发 2
        GatewaySession a = session(1, "9.9.9.9");
        GatewaySession b = session(2, "9.9.9.9"); // 同 IP，共享桶
        filter.onConnect(a);
        filter.onConnect(b);

        assertEquals(GatewayErrorCode.OK, filter.onPacket(a, packet(2000)));
        assertEquals(GatewayErrorCode.OK, filter.onPacket(b, packet(2000)));
        assertEquals(GatewayErrorCode.CONNECTION_LIMITED, filter.onPacket(a, packet(2000)),
                "同 IP 聚合速率超限");
    }

    @Test
    void chainReturnsFirstBlockingCodeAndShortCircuits() {
        AtomicInteger reachedB = new AtomicInteger();
        GatewayFilter blockA = new GatewayFilter() {
            @Override
            public int onPacket(GatewaySession s, GatewayPacket p) {
                return GatewayErrorCode.RATE_LIMITED;
            }
        };
        GatewayFilter spyB = new GatewayFilter() {
            @Override
            public int onPacket(GatewaySession s, GatewayPacket p) {
                reachedB.incrementAndGet();
                return GatewayErrorCode.OK;
            }
        };
        FilterChain chain = new FilterChain(List.of(blockA, spyB));

        assertEquals(GatewayErrorCode.RATE_LIMITED, chain.onPacket(session(1, "1.1.1.1"), packet(2000)));
        assertEquals(0, reachedB.get(), "前置拦截后不应再问后续过滤器");
    }

    @Test
    void chainRollsBackAcceptedFiltersWhenLaterRejectsConnect() {
        AtomicInteger aConnect = new AtomicInteger();
        AtomicInteger aDisconnect = new AtomicInteger();
        GatewayFilter acceptA = new GatewayFilter() {
            @Override
            public boolean onConnect(GatewaySession s) {
                aConnect.incrementAndGet();
                return true;
            }

            @Override
            public int onPacket(GatewaySession s, GatewayPacket p) {
                return GatewayErrorCode.OK;
            }

            @Override
            public void onDisconnect(GatewaySession s) {
                aDisconnect.incrementAndGet();
            }
        };
        GatewayFilter rejectB = new GatewayFilter() {
            @Override
            public boolean onConnect(GatewaySession s) {
                return false;
            }

            @Override
            public int onPacket(GatewaySession s, GatewayPacket p) {
                return GatewayErrorCode.OK;
            }
        };
        FilterChain chain = new FilterChain(List.of(acceptA, rejectB));

        assertFalse(chain.onConnect(session(1, "1.1.1.1")));
        assertEquals(1, aConnect.get());
        assertEquals(1, aDisconnect.get(), "后置过滤器拒绝时，已接受的前置过滤器应被回滚清理");
    }
}
