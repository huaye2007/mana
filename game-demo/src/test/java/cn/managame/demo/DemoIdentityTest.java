package cn.managame.demo;

import cn.managame.demo.bus.role.RoleHandler;
import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.demo.network.message.PingMessage;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.network.connection.Connection;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.handler.HandlerMethod;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class DemoIdentityTest {
    record Received(long roleId, PingMessage request, HandlerContext context, boolean virtual) {}
    @Profile("manual-identity-probe-only")
    static class Probe extends RoleHandler {
        final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
        @Override @HandlerMethod public void ping(ClientHandlerContext context, PingMessage request) {
            received.add(new Received(context.businessId(), request, context, Thread.currentThread().isVirtual()));
        }
    }

    @Test void contextSuppliesIdentityWithoutAWrapperOrArgumentBinding() throws Exception {
        var probe = new Probe();
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean("roleHandler", RoleHandler.class, () -> probe);
            spring.register(GameRuntimeConfig.class); spring.refresh();
            var runtime = spring.getBean(GameRuntime.class);
            var request = new PingMessage(1234);
            runtime.dispatch(null, 99L, GameDomain.ROLE_BUSINESS_ID_TYPE, 10001L, request);
            var received = probe.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(received); assertEquals(10001L, received.roleId());
            assertSame(request, received.request()); assertEquals(1, received.context().routeDomain());
            assertEquals(99, received.context().routeKey()); assertEquals(GameDomain.ROLE_BUSINESS_ID_TYPE, received.context().businessIdType());
            assertEquals(10001, received.context().businessId()); assertTrue(received.virtual());
            var connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("get")) return null;
                        throw new AssertionError("Unexpected Connection operation");
                    });
            assertThrows(IllegalArgumentException.class, () -> runtime.dispatch(connection, request));
            assertTrue(probe.received.isEmpty());
        }
    }
}
