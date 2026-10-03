package cn.managame.demo;

import cn.managame.demo.bus.user.LoginReq;
import cn.managame.demo.bus.user.UserHandler;
import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.network.connection.Connection;
import cn.managame.runtime.GameRuntime;
import cn.managame.core.FrameworkErrorCodes;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.handler.HandlerMethod;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

import java.lang.reflect.Proxy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DemoHandlerTest {
    record Received(Connection connection, LoginReq request, int domain, long key, boolean virtual) {}
    @Profile("manual-handler-probe-only")
    static class Probe extends UserHandler {
        final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
        @Override @HandlerMethod(domain = GameDomain.LOGIN_ID) public void login(ClientHandlerContext handlerContext, LoginReq request) {
            var context = Contexts.current();
            received.add(new Received(handlerContext.connection(), request, context.routeDomain(), context.routeKey(), Thread.currentThread().isVirtual()));
        }
    }

    @Test void markerOnlyUserHandlerReceivesLoginWithoutAnApplicationRouteOrContext() throws Exception {
        var probe = new Probe();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("userHandler", UserHandler.class, () -> probe);
            context.register(GameRuntimeConfig.class);
            context.refresh();
            assertSame(probe, context.getBean(UserHandler.class));
            var request = new LoginReq(); request.setUserId(10001L);
            var connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> { throw new AssertionError("Unexpected Connection operation"); });
            context.getBean(GameRuntime.class).dispatch(connection, 99L, request);
            Received received = probe.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(received);
            assertSame(connection, received.connection()); assertSame(request, received.request());
            assertEquals(GameDomain.LOGIN_ID, received.domain()); assertEquals(99L, received.key()); assertTrue(received.virtual());
            var runtime = context.getBean(GameRuntime.class);
            runtime.dispatch(connection, request);
            received = probe.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(received); assertEquals(10001L, received.key());
            assertEquals(GameDomain.LOGIN_ID, received.domain());
            request.setUserId(0L);
            assertEquals(FrameworkErrorCodes.INVALID_ROUTE_KEY,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(connection, request)).errorCode());
            assertTrue(probe.received.isEmpty());
        }
    }
}
