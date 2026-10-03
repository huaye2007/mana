package cn.managame.runtime;

import cn.managame.core.Metadatas;
import cn.managame.core.MetadataKeys;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.event.Event;
import cn.managame.runtime.event.EventMethod;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteDomain;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.managame.core.FrameworkErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class TransportContextTest {
    record TcpMessage(Runnable action) {}
    record RpcMessage(Runnable action) {}
    record LocalMessage(Runnable action) {}
    record Notice(int routeDomain, long routeKey) implements Event {}

    @Handler(domain = 1)
    static class Handlers {
        @HandlerMethod public void tcp(ClientHandlerContext context, TcpMessage message) {
            assertSame(context, Contexts.current()); message.action().run();
        }
        @HandlerMethod public void rpc(RpcMessage message, RpcHandlerContext context) {
            assertSame(context, Contexts.current()); message.action().run();
        }
        @HandlerMethod public void local(HandlerContext context, LocalMessage message) {
            assertSame(context, Contexts.current()); message.action().run();
        }
    }

    GameRuntimeBuilder builder(RouteExecutor executor, List<RuntimeError> errors) {
        return GameRuntimeBuilder.builder().routeDomains(List.of(RouteDomain.of(1, "role"), RouteDomain.of(2, "guild")))
                .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1, 2)))
                .protocols(List.of(r -> {
                    r.register(Protocols.request(1, TcpMessage.class));
                    r.register(Protocols.request(2, RpcMessage.class));
                    r.register(Protocols.notify(3, LocalMessage.class));
                })).handlers(List.of(new Handlers())).errorHandler(errors::add);
    }

    DefaultRpcHandlerContext rpc(int domain, long key, Object message, int requestId) {
        return new DefaultRpcHandlerContext(domain, key, 7, 10001, Metadatas.empty(), message, -10, 254, -2, requestId);
    }

    @Test void tcpAndRpcShareSerialRouteAndAdmittedContextsSurviveClose() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var done = new CountDownLatch(2); var order = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<RuntimeError>();
        var runtime = builder(RouteExecutors.virtualThreads(), errors).build();
        var connection = HandlerDispatchTest.connection();
        var tcp = new DefaultClientHandlerContext(1, 99, new TcpMessage(() -> {
            try {
                assertSame(connection, Contexts.current(ClientHandlerContext.class).connection());
                order.add("tcp-enter"); entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS)); order.add("tcp-exit");
            } catch (InterruptedException e) { throw new AssertionError(e); }
            finally { done.countDown(); }
        }), connection);
        var rpc = rpc(1, 99, new RpcMessage(() -> {
            try {
                var current = Contexts.current(RpcHandlerContext.class);
                assertEquals(-10, current.sourceNodeId()); assertEquals(254, current.sourceSlotId());
                assertEquals(-2, current.command()); assertEquals(Integer.MIN_VALUE, current.requestId());
                assertEquals(7, current.businessIdType()); assertEquals(10001, current.businessId());
                assertThrows(ClassCastException.class, () -> Contexts.current(ClientHandlerContext.class));
                order.add("rpc");
            } finally { done.countDown(); }
        }), Integer.MIN_VALUE);
        try {
            runtime.dispatch(tcp); assertTrue(entered.await(5, TimeUnit.SECONDS)); runtime.dispatch(rpc);
            assertEquals(List.of("tcp-enter"), order);
            runtime.close();
            assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(rpc)).errorCode());
            release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("tcp-enter", "tcp-exit", "rpc"), order); assertTrue(errors.isEmpty());
        } finally { release.countDown(); runtime.close(); }
        assertNull(Contexts.currentOrNull());
    }

    @Test void wrongTransportContextRejectsBeforeAdmissionAndGenericHandlerAcceptsEither() {
        var submissions = new AtomicInteger(); var executions = new AtomicInteger();
        var errors = new ArrayList<RuntimeError>();
        try (var runtime = builder((d, k, task) -> {
            submissions.incrementAndGet(); task.run(); return RouteExecuteStatus.ACCEPTED;
        }, errors).build()) {
            Object tcp = new TcpMessage(() -> fail("Rejected Handler ran"));
            Object rpc = new RpcMessage(() -> fail("Rejected Handler ran"));
            for (var context : List.of(rpc(1, 99, tcp, 0), new DefaultClientHandlerContext(1, 99, rpc, null),
                    new DefaultHandlerContext(1, 99, tcp), new DefaultHandlerContext(1, 99, rpc))) {
                assertEquals(HANDLER_CONTEXT_MISMATCH,
                        assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(context)).errorCode());
            }
            assertEquals(ROUTE_DOMAIN_MISMATCH, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.dispatch(rpc(2, 99, rpc, 1))).errorCode());
            assertEquals(INVALID_ROUTE_KEY, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.dispatch(rpc(1, 0, rpc, 1))).errorCode());
            assertEquals(0, submissions.get());
            var local = new LocalMessage(executions::incrementAndGet);
            runtime.dispatch(new DefaultHandlerContext(1, 99, local));
            runtime.dispatch(new DefaultClientHandlerContext(1, 99, local, null));
            runtime.dispatch(rpc(1, 99, local, 0));
            assertEquals(3, executions.get()); assertTrue(errors.isEmpty());
        }
    }

    @Test void nestedTcpAndDerivedContextsRestoreRpcWithoutCopyingTransportFields() {
        var errors = new ArrayList<RuntimeError>(); var observed = new ArrayList<Context>();
        var metadata = Metadatas.builder().put(MetadataKeys.longKey(1), 77L).build();
        class Events {
            @EventMethod public void notice(Notice event) { observed.add(Contexts.current(EventContext.class)); }
        }
        try (var runtime = builder((d, k, task) -> { task.run(); return RouteExecuteStatus.ACCEPTED; }, errors)
                .eventHandlers(List.of(new Events())).build()) {
            var request = new RpcMessage(() -> {
                var source = Contexts.current(RpcHandlerContext.class);
                runtime.dispatch(null, 99L, new TcpMessage(() -> {
                    assertNull(Contexts.current(ClientHandlerContext.class).connection());
                    assertEquals(0, Contexts.current(ClientHandlerContext.class).businessId());
                }));
                assertSame(source, Contexts.current());
                runtime.eventBus().publish(new Notice(1, 99)); assertSame(source, Contexts.current());
                runtime.call(2, 88, () -> { observed.add(Contexts.current(RouteCallContext.class)); return 42; },
                        new RouteCallback<Integer>() {
                            public void onSuccess(Integer value) { assertEquals(42, value); assertSame(source, Contexts.current()); }
                            public void onFail(int code) { fail("Unexpected rejection " + code); }
                        });
                assertSame(source, Contexts.current());
                assertEquals(0, source.requestId());
            });
            var context = new DefaultRpcHandlerContext(1, 99, 7, 10001, metadata, request, 10, 0, 2, 0);
            runtime.dispatch(context);
            assertEquals(2, observed.size());
            for (var derived : observed) {
                var identity = assertInstanceOf(InvocationContext.class, derived);
                assertEquals(7, identity.businessIdType()); assertEquals(10001, identity.businessId());
                assertSame(metadata, identity.metadata());
                assertFalse(derived instanceof RpcHandlerContext); assertFalse(derived instanceof ClientHandlerContext);
            }
            assertTrue(errors.isEmpty()); assertNull(Contexts.currentOrNull());
        }
    }

    @Test void rpcEnvelopeRejectsInvalidOriginsButPreservesUnsignedWireBitPatterns() {
        var message = new RpcMessage(() -> {});
        assertThrows(IllegalArgumentException.class, () -> new DefaultRpcHandlerContext(1, 99, 7, 1,
                Metadatas.empty(), message, 0, 0, 2, 1));
        for (int slot : List.of(-1, 255)) assertThrows(IllegalArgumentException.class,
                () -> new DefaultRpcHandlerContext(1, 99, 7, 1, Metadatas.empty(), message, 10, slot, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new DefaultRpcHandlerContext(1, 99, 7, 1,
                Metadatas.empty(), message, 10, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DefaultRpcHandlerContext(1, 99, 256, 1,
                Metadatas.empty(), message, 10, 0, 2, 1));
        assertSame(message, rpc(1, 99, message, -1).message());
    }
}
