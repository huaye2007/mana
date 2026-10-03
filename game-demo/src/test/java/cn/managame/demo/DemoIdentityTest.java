package cn.managame.demo;

import cn.managame.demo.bus.role.RoleHandler;
import cn.managame.demo.bus.role.RoleId;
import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.demo.network.message.PingMessage;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.handler.HandlerMethod;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DemoIdentityTest {
    record Received(RoleId role, PingMessage request, HandlerContext context, boolean virtual) {}
    @Profile("manual-identity-probe-only")
    static class Probe extends RoleHandler {
        final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
        @Override @HandlerMethod public void ping(RoleId roleId, PingMessage request) {
            received.add(new Received(roleId, request, (HandlerContext) Contexts.current(), Thread.currentThread().isVirtual()));
        }
    }

    @Test void applicationRoleTypeBindsIdentityWithoutUsingRouteKeyOrProtocolData() throws Exception {
        var probe = new Probe();
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean("roleHandler", RoleHandler.class, () -> probe);
            spring.register(GameRuntimeConfig.class); spring.refresh();
            var runtime = spring.getBean(GameRuntime.class);
            var request = new PingMessage(1234);
            runtime.dispatch(null, 99L, RoleId.TYPE, 10001L, request);
            var received = probe.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(received); assertEquals(new RoleId(10001), received.role());
            assertSame(request, received.request()); assertEquals(1, received.context().routeDomain());
            assertEquals(99, received.context().routeKey()); assertEquals(RoleId.TYPE, received.context().businessIdType());
            assertEquals(10001, received.context().businessId()); assertTrue(received.virtual());
            assertThrows(IllegalArgumentException.class, () -> runtime.dispatch(null, 99L, request));
            assertTrue(probe.received.isEmpty());
        }
    }
}
