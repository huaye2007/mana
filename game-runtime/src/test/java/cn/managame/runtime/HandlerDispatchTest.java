package cn.managame.runtime;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import cn.managame.core.FrameworkErrorCodes;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.*;
import cn.managame.network.connection.Connection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandlerDispatchTest {
    record FieldRequest(long userId) {}
    record MethodRequest(Integer id) { public Integer key() { return id; } }
    record Received(Connection connection, Object message, ClientHandlerContext context) {}

    @Handler(domain = 1, routeKey = "userId")
    static class Handlers {
        final Queue<Received> received = new ConcurrentLinkedQueue<>();
        @HandlerMethod public void field(ClientHandlerContext context, FieldRequest message) {
            received.add(new Received(context.connection(), message, context));
        }
        @HandlerMethod(domain = 2, routeKeyMethod = "key")
        public void method(MethodRequest message, ClientHandlerContext context) {
            received.add(new Received(context.connection(), message, context));
        }
    }

    static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> { throw new AssertionError("Runtime must not call Connection." + method.getName()); });
    }

    static GameRuntimeBuilder builder(Object handler, RouteExecutor executor, List<RuntimeError> errors) {
        return GameRuntimeBuilder.builder().routeDomains(List.of(RouteDomain.of(1, "one"), RouteDomain.of(2, "two")))
                .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1, 2)))
                .protocols(List.of(registrar -> {
                    registrar.register(Protocols.request(1, FieldRequest.class));
                    registrar.register(Protocols.request(2, MethodRequest.class));
                })).handlers(List.of(handler)).errorHandler(errors::add);
    }

    @Test void automaticDispatchResolvesDomainAndKeyAndPreservesConnectionInContext() {
        var handler = new Handlers();
        var tasks = new ArrayDeque<Runnable>();
        var routes = new ArrayList<String>();
        var errors = new ArrayList<RuntimeError>();
        RouteExecutor executor = (domain, key, task) -> {
            routes.add(domain + "/" + key); tasks.add(task); return RouteExecuteStatus.ACCEPTED;
        };
        try (var runtime = builder(handler, executor, errors).build()) {
            Connection connection = connection();
            var field = new FieldRequest(42);
            var method = new MethodRequest(-7);
            runtime.dispatch(connection, field);
            runtime.dispatch(connection, method);
            assertEquals(List.of("1/42", "2/-7"), routes);
            assertTrue(handler.received.isEmpty());
            tasks.remove().run(); tasks.remove().run();
            var first = handler.received.remove();
            var second = handler.received.remove();
            assertSame(field, first.message()); assertSame(method, second.message());
            assertSame(connection, first.connection()); assertSame(connection, second.connection());
            assertSame(connection, first.context().connection());
            assertEquals(0, first.context().businessIdType()); assertEquals(0, first.context().businessId());
            assertTrue(first.context().metadata().isEmpty());
            assertNull(Contexts.currentOrNull()); assertTrue(errors.isEmpty());
        }
    }

    @Test void suppliedKeyWinsWithoutExtractionAndMissingOrZeroKeyRejects() {
        var submissions = new AtomicInteger();
        RouteExecutor executor = (domain, key, task) -> { submissions.incrementAndGet(); return RouteExecuteStatus.ACCEPTED; };
        try (var runtime = builder(new Handlers(), executor, new ArrayList<>()).build()) {
            runtime.dispatch(null, 99, new FieldRequest(0));
            runtime.dispatch(connection(), 7, new MethodRequest(null));
            assertEquals(FrameworkErrorCodes.INVALID_ROUTE_KEY,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(connection(), new FieldRequest(0))).errorCode());
            assertThrows(NullPointerException.class, () -> runtime.dispatch(connection(), new MethodRequest(null)));
            assertEquals(FrameworkErrorCodes.HANDLER_NOT_FOUND,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(connection(), "unknown")).errorCode());
            assertEquals(FrameworkErrorCodes.INVALID_ROUTE_KEY,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(null, 0, new FieldRequest(1))).errorCode());
            assertEquals(2, submissions.get());
        }
        @Handler(domain = 1) class NoKey {
            @HandlerMethod public void field(FieldRequest request) {}
        }
        try (var runtime = builder(new NoKey(), executor, new ArrayList<>()).build()) {
            assertEquals(FrameworkErrorCodes.INVALID_ROUTE_KEY,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(null, new FieldRequest(1))).errorCode());
            runtime.dispatch(new DefaultHandlerContext(1, 99, new FieldRequest(1)));
            runtime.dispatch(null, 88, new FieldRequest(0));
            assertEquals(4, submissions.get());
        }
    }

    @Test void explicitContextKeepsItsRouteAndConnectionSnapshotAfterShutdown() {
        var handler = new Handlers();
        var tasks = new ArrayDeque<Runnable>();
        var errors = new ArrayList<RuntimeError>();
        var reads = new AtomicInteger();
        Connection original = connection();
        var context = new DefaultClientHandlerContext(1, 99, new FieldRequest(42), null) {
            @Override public Connection connection() { reads.incrementAndGet(); return original; }
        };
        var runtime = builder(handler, (d, k, t) -> { tasks.add(t); return RouteExecuteStatus.ACCEPTED; }, errors).build();
        try {
            runtime.dispatch(context);
            runtime.close();
            assertEquals(FrameworkErrorCodes.RUNTIME_CLOSED,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(original, new FieldRequest(42))).errorCode());
            tasks.remove().run();
            var received = handler.received.remove();
            assertSame(context, received.context()); assertSame(original, received.connection());
            assertEquals(99, received.context().routeKey()); assertEquals(1, reads.get());
            assertTrue(errors.isEmpty());
        } finally { runtime.close(); }
    }

    @Test void annotationConflictAndInvalidConnectionSignaturesFailAtBuild() {
        RouteExecutor executor = (d, k, t) -> RouteExecuteStatus.ACCEPTED;
        var errors = new ArrayList<RuntimeError>();
        assertThrows(IllegalArgumentException.class, () -> builder(new Handlers(), executor, errors)
                .routeKeys(List.of(RouteKeyBinding.of(FieldRequest.class, FieldRequest::userId))).build());
        @Handler(domain = 1, routeKey = "userId", routeKeyMethod = "userId") class Ambiguous {
            @HandlerMethod public void field(FieldRequest request) {}
        }
        @Handler(domain = 1, routeKey = "absent") class Missing {
            @HandlerMethod public void field(FieldRequest request) {}
        }
        @Handler(domain = 1) class DuplicateConnection {
            @HandlerMethod public void field(Connection first, FieldRequest request, Connection second) {}
        }
        for (Object invalid : List.of(new Ambiguous(), new Missing(), new DuplicateConnection())) {
            assertThrows(IllegalArgumentException.class, () -> builder(invalid, executor, errors).build());
        }
    }

    static class Parent { private long id = Long.MIN_VALUE; }
    static class Members extends Parent {
        private Integer value = 3;
        private String text = "3";
        private static long shared;
        public Long getValue() { return value.longValue(); }
        public long broken() { throw new IllegalStateException("key failure"); }
        public static long staticKey() { return 1; }
        public long withArgument(long value) { return value; }
    }

    @Test void memberBindingsCompileInheritedFieldsGettersAndIntegralValues() {
        var message = new Members();
        assertEquals(Long.MIN_VALUE, RouteKeyBinding.ofField(Members.class, "id").extractor().extract(message));
        var field = RouteKeyBinding.ofField(Members.class, "value");
        assertEquals(3, field.extractor().extract(message));
        message.value = 8;
        assertEquals(8, field.extractor().extract(message));
        assertEquals(8, RouteKeyBinding.ofMethod(Members.class, "getValue").extractor().extract(message));
        assertEquals("key failure", assertThrows(IllegalStateException.class,
                () -> RouteKeyBinding.ofMethod(Members.class, "broken").extractor().extract(message)).getMessage());
        message.value = null;
        assertThrows(NullPointerException.class, () -> field.extractor().extract(message));
        for (String name : List.of("absent", "text", "shared", " ")) {
            assertThrows(IllegalArgumentException.class, () -> RouteKeyBinding.ofField(Members.class, name));
        }
        for (String name : List.of("absent", "staticKey", "withArgument", " ")) {
            assertThrows(IllegalArgumentException.class, () -> RouteKeyBinding.ofMethod(Members.class, name));
        }
    }

    @Test void automaticDispatchSerializesSameKeysAndReportsHandlerFailures() throws Exception {
        var completed = new LinkedBlockingQueue<Long>();
        var active = new AtomicInteger();
        var errors = new CopyOnWriteArrayList<RuntimeError>();
        @Handler(domain = 1, routeKeyMethod = "userId") class Serial {
            @HandlerMethod public void field(ClientHandlerContext context, FieldRequest request) {
                assertEquals(1, active.incrementAndGet());
                try { completed.add(Contexts.current().routeKey()); throw new IllegalArgumentException("handler failure"); }
                finally { active.decrementAndGet(); }
            }
        }
        try (var runtime = builder(new Serial(), RouteExecutors.virtualThreads(), errors).build()) {
            for (int i = 0; i < 20; i++) runtime.dispatch(connection(), new FieldRequest(7));
            for (int i = 0; i < 20; i++) assertEquals(7L, completed.poll(5, TimeUnit.SECONDS));
            // A later same-Route barrier confirms the final error callback completed.
            var done = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);
            runtime.timer().schedule(1, 7, java.time.Duration.ZERO, () -> done.trySuccess(null));
            done.get(5, TimeUnit.SECONDS);
            assertEquals(20, errors.size());
            assertTrue(errors.stream().allMatch(e -> e.errorCode() == FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR));
        }
    }
}
