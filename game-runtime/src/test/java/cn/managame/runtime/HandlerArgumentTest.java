package cn.managame.runtime;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.core.Metadatas;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.handler.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandlerArgumentTest {
    record RoleId(long value) {}
    record Identity(int type, long value) {}
    @Handler(domain = 1)
    static class BusinessHandler {
        final List<List<Object>> received = new ArrayList<>();
        @HandlerMethod(domain = 2, routeKey = "userId")
        public void handle(RoleId role, HandlerDispatchTest.FieldRequest request,
                           DefaultHandlerContext context, Identity identity) {
            assertSame(context, Contexts.current());
            received.add(List.of(role, request, context, identity));
        }
    }

    static HandlerArgumentBinding<RoleId> roles(AtomicInteger calls) {
        return HandlerArgumentBinding.of(RoleId.class, context -> {
            calls.incrementAndGet();
            if (context.businessIdType() != 7) throw new IllegalArgumentException("Expected role identity");
            return new RoleId(context.businessId());
        });
    }

    @Test void typedIdentityIsResolvedOnceBeforeAdmissionAndIndependentOfRouteAndMessage() {
        var calls = new AtomicInteger();
        var order = new ArrayList<String>();
        Thread submittingThread = Thread.currentThread();
        var handler = new BusinessHandler();
        var tasks = new ArrayDeque<Runnable>();
        var routes = new ArrayList<String>();
        RouteExecutor executor = (d, k, t) -> { routes.add(d + "/" + k); tasks.add(t); return RouteExecuteStatus.ACCEPTED; };
        try (var runtime = HandlerDispatchTest.builder(handler, executor, new ArrayList<>())
                .handlerArguments(List.of(HandlerArgumentBinding.of(RoleId.class, c -> {
                    assertSame(submittingThread, Thread.currentThread()); assertNull(Contexts.currentOrNull());
                    order.add("role"); return roles(calls).resolve(c);
                }), HandlerArgumentBinding.of(Identity.class, c -> {
                    order.add("identity"); return new Identity(c.businessIdType(), c.businessId());
                }))).build()) {
            var connection = HandlerDispatchTest.connection();
            var request = new HandlerDispatchTest.FieldRequest(42);
            runtime.dispatch(connection, 99L, 7, Long.MIN_VALUE, request);
            runtime.dispatch(connection, 7, 10001L, request);
            var context = new DefaultHandlerContext(2, 88, 7, 0L, Metadatas.empty(), request, connection);
            runtime.dispatch(context);
            assertEquals(List.of("2/99", "2/42", "2/88"), routes);
            assertEquals(3, calls.get()); assertTrue(handler.received.isEmpty());
            assertEquals(List.of("role", "identity", "role", "identity", "role", "identity"), order);
            runtime.close();
            while (!tasks.isEmpty()) tasks.remove().run();
            assertEquals(3, calls.get()); assertEquals(3, handler.received.size());
            assertEquals(new RoleId(Long.MIN_VALUE), handler.received.get(0).get(0));
            assertSame(request, handler.received.get(0).get(1));
            var first = (DefaultHandlerContext) handler.received.get(0).get(2);
            assertSame(connection, first.connection()); assertEquals(99, first.routeKey());
            assertEquals(new Identity(7, Long.MIN_VALUE), handler.received.get(0).get(3));
            assertEquals(new RoleId(10001), handler.received.get(1).get(0));
            assertSame(context, handler.received.get(2).get(2));
            assertEquals(new RoleId(0), handler.received.get(2).get(0));
        }
    }

    @Test void invalidIdentityAndResolverFailuresRejectBeforeQueueAdmission() {
        var calls = new AtomicInteger();
        var submissions = new AtomicInteger();
        var errors = new ArrayList<cn.managame.runtime.error.RuntimeError>();
        RouteExecutor executor = (d, k, t) -> { submissions.incrementAndGet(); return RouteExecuteStatus.ACCEPTED; };
        var identity = HandlerArgumentBinding.of(Identity.class, c -> new Identity(c.businessIdType(), c.businessId()));
        var request = new HandlerDispatchTest.FieldRequest(42);
        try (var runtime = HandlerDispatchTest.builder(new BusinessHandler(), executor, errors)
                .handlerArguments(List.of(roles(calls), identity)).build()) {
            assertThrows(IllegalArgumentException.class, () -> runtime.dispatch(null, 99L, request));
            assertEquals(1, calls.get());
            assertEquals(FrameworkErrorCodes.INVALID_ROUTE_KEY,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(null, 0L, 7, 1L, request)).errorCode());
            assertThrows(IllegalArgumentException.class, () -> runtime.dispatch(null, 99L, 256, 1L, request));
            assertEquals(1, calls.get());
        }
        var failure = new IllegalStateException("resolver failed");
        try (var runtime = HandlerDispatchTest.builder(new BusinessHandler(), executor, errors)
                .handlerArguments(List.of(HandlerArgumentBinding.of(RoleId.class, c -> { throw failure; }), identity)).build()) {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> runtime.dispatch(null, 99L, 7, 1L, request)));
        }
        try (var runtime = HandlerDispatchTest.builder(new BusinessHandler(), executor, errors)
                .handlerArguments(List.of(HandlerArgumentBinding.of(RoleId.class, c -> null), identity)).build()) {
            assertThrows(NullPointerException.class, () -> runtime.dispatch(null, 99L, 7, 1L, request));
        }
        assertEquals(0, submissions.get()); assertTrue(errors.isEmpty());
        try (var runtime = HandlerDispatchTest.builder(new BusinessHandler(), executor, errors)
                .handlerArguments(List.of(wrongType(), identity)).build()) {
            assertThrows(ClassCastException.class, () -> runtime.dispatch(null, 99L, 7, 1L, request));
            assertEquals(0, submissions.get()); assertTrue(errors.isEmpty());
        }
        try (var runtime = HandlerDispatchTest.builder(new BusinessHandler(), (d, k, t) -> RouteExecuteStatus.OVERLOADED, errors)
                .handlerArguments(List.of(roles(calls), identity)).build()) {
            assertEquals(FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(null, 99L, 7, 1L, request)).errorCode());
            assertEquals(2, calls.get());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static HandlerArgumentBinding<?> wrongType() {
        return HandlerArgumentBinding.of((Class) RoleId.class, context -> "wrong type");
    }

    @Test void invalidOrAmbiguousBindingsFailAtStartup() {
        var role = roles(new AtomicInteger());
        var identity = HandlerArgumentBinding.of(Identity.class, c -> new Identity(c.businessIdType(), c.businessId()));
        RouteExecutor executor = (d, k, t) -> RouteExecuteStatus.ACCEPTED;
        for (var bindings : List.of(List.of(role), List.of(role, role, identity),
                List.of(role, identity, HandlerArgumentBinding.of(HandlerDispatchTest.FieldRequest.class,
                        c -> (HandlerDispatchTest.FieldRequest) c.message())))) {
            assertThrows(IllegalArgumentException.class, () -> HandlerDispatchTest.builder(new BusinessHandler(), executor,
                    new ArrayList<>()).handlerArguments(bindings).build());
        }
        @Handler(domain = 1) class Repeated {
            @HandlerMethod public void handle(RoleId first, RoleId second, HandlerDispatchTest.FieldRequest request) {}
        }
        assertThrows(IllegalArgumentException.class, () -> HandlerDispatchTest.builder(new Repeated(), executor,
                new ArrayList<>()).handlerArguments(List.of(role)).build());
        assertThrows(IllegalArgumentException.class, () -> HandlerArgumentBinding.of(long.class, c -> 1L));
        assertThrows(IllegalArgumentException.class, () -> HandlerArgumentBinding.of(HandlerContext.class, c -> c));
    }
}
