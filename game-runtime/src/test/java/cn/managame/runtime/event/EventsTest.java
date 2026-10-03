package cn.managame.runtime.event;

import cn.managame.core.Metadatas;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.RouteDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static cn.managame.core.FrameworkErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class EventsTest {
    record Notice(int routeDomain, long routeKey) implements Event {}
    record Request(Runnable action) {}
    record Response() {}
    record Seen(Notice event, EventContext context) {}
    static class Listener {
        final LinkedBlockingQueue<Seen> seen = new LinkedBlockingQueue<>();
        @EventMethod public void onNotice(Notice event) {
            seen.add(new Seen(event, (EventContext) Contexts.current()));
        }
    }
    @Handler(domain = 1) static class Requests {
        @HandlerMethod public void run(Request request) { request.action().run(); }
    }
    private final List<GameRuntime> runtimes = new ArrayList<>();
    @AfterEach void close() { runtimes.forEach(GameRuntime::close); }

    private GameRuntime runtime(Listener listener) {
        var runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "events")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1)))
                .eventHandlers(List.of(listener)).handlers(List.of(new Requests()))
                .protocols(List.of(reg -> {
                    reg.register(Protocols.request(1, Request.class));
                    reg.register(Protocols.response(1, Response.class));
                    reg.bindResponse(Request.class, Response.class);
                })).build();
        runtimes.add(runtime);
        return runtime;
    }

    @Test void missingRuntimeAndNullArgumentsAreRejected() {
        assertThrows(IllegalStateException.class, () -> Events.publish(new Notice(1, 7)));
        assertThrows(NullPointerException.class, () -> Events.publish(null));
        assertThrows(NullPointerException.class, () -> Events.bind(null));
        assertThrows(NullPointerException.class, () -> Events.unbind(null));
    }

    @Test void externalPublicationUsesDefaultAndCloseReleasesIt() throws Exception {
        var listener = new Listener(); var runtime = runtime(listener);
        Events.bind(runtime); Events.bind(runtime);
        var event = new Notice(1, 7);
        Events.publish(event);
        Seen seen = listener.seen.poll(5, TimeUnit.SECONDS);
        assertNotNull(seen); assertSame(event, seen.event());
        assertEquals(7, seen.context().routeKey());
        assertEquals(0, seen.context().businessIdType());
        assertEquals(0, seen.context().businessId());
        assertTrue(seen.context().metadata().isEmpty());
        assertEquals(INVALID_ROUTE_KEY, assertThrows(RuntimeDispatchException.class,
                () -> Events.publish(new Notice(1, 0))).errorCode());
        runtime.close();
        assertThrows(IllegalStateException.class, () -> Events.publish(event));
    }

    @Test void currentRuntimeWinsAndSameRouteInlinesWithIdentityAndContextRestoration() throws Exception {
        var ownListener = new Listener(); var otherListener = new Listener();
        var owner = runtime(ownListener); var other = runtime(otherListener); Events.bind(other);
        var result = new CompletableFuture<Seen>();
        var restored = new CompletableFuture<Context>();
        var source = new DefaultHandlerContext(1, 7, 4, 99, Metadatas.empty(), new Request(() -> {
            try {
                Events.publish(new Notice(1, 7));
                result.complete(ownListener.seen.poll());
                restored.complete(Contexts.current());
            } catch (Throwable error) { result.completeExceptionally(error); restored.completeExceptionally(error); }
        }));
        owner.dispatch(source);
        Seen seen = result.get(5, TimeUnit.SECONDS);
        assertNotNull(seen, "Same-route publication should complete inline");
        assertEquals(4, seen.context().businessIdType());
        assertEquals(99, seen.context().businessId());
        assertSame(source.metadata(), seen.context().metadata());
        assertSame(source, restored.get(5, TimeUnit.SECONDS));
        assertNull(otherListener.seen.poll());
    }

    @Test void currentRuntimePublicationNeedsNoDefaultBinding() throws Exception {
        var listener = new Listener(); var runtime = runtime(listener);
        runtime.dispatch(new DefaultHandlerContext(1, 7, new Request(() -> Events.publish(new Notice(1, 8)))));
        Seen seen = listener.seen.poll(5, TimeUnit.SECONDS);
        assertNotNull(seen); assertEquals(8, seen.context().routeKey());
        assertThrows(IllegalStateException.class, () -> Events.publish(new Notice(1, 7)));
    }

    @Test void conflictingBindingAndOldRuntimeClosureCannotRemoveAnotherBinding() throws Exception {
        var first = runtime(new Listener()); var listener = new Listener(); var second = runtime(listener);
        Events.bind(first);
        assertThrows(IllegalStateException.class, () -> Events.bind(second));
        assertFalse(Events.unbind(second)); assertTrue(Events.unbind(first));
        Events.bind(second); first.close();
        Events.publish(new Notice(1, 7));
        assertNotNull(listener.seen.poll(5, TimeUnit.SECONDS));
    }

    @Test void closedCurrentRuntimeDoesNotFallBackToAnOpenDefault() throws Exception {
        var owner = runtime(new Listener()); var otherListener = new Listener(); Events.bind(runtime(otherListener));
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var outcome = new CompletableFuture<Throwable>();
        owner.dispatch(new DefaultHandlerContext(1, 7, new Request(() -> {
            entered.countDown();
            try { proceed.await(); Events.publish(new Notice(1, 7)); outcome.complete(null); }
            catch (Throwable error) { outcome.complete(error); }
        })));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); owner.close(); proceed.countDown();
            var failure = assertInstanceOf(RuntimeDispatchException.class, outcome.get(5, TimeUnit.SECONDS));
            assertEquals(RUNTIME_CLOSED, failure.errorCode()); assertNull(otherListener.seen.poll());
        } finally { proceed.countDown(); }
    }

    @Test void selectedDefaultIsNotRedirectedByConcurrentUnbinding() throws Exception {
        var firstListener = new Listener(); var first = runtime(firstListener);
        var secondListener = new Listener(); var second = runtime(secondListener);
        var selected = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var gated = (GameRuntime) Proxy.newProxyInstance(GameRuntime.class.getClassLoader(),
                new Class[]{GameRuntime.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "eventBus" -> {
                        selected.countDown();
                        if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Publication stalled");
                        yield first.eventBus();
                    }
                    case "close" -> { Events.unbind((GameRuntime) proxy); first.close(); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        runtimes.add(gated); Events.bind(gated);
        var done = new CompletableFuture<Void>();
        Thread publisher = Thread.ofPlatform().start(() -> {
            try { Events.publish(new Notice(1, 7)); done.complete(null); }
            catch (Throwable error) { done.completeExceptionally(error); }
        });
        try {
            assertTrue(selected.await(5, TimeUnit.SECONDS));
            assertTrue(Events.unbind(gated)); Events.bind(second); proceed.countDown();
            done.get(5, TimeUnit.SECONDS);
            assertNotNull(firstListener.seen.poll(5, TimeUnit.SECONDS)); assertNull(secondListener.seen.poll());
        } finally { proceed.countDown(); publisher.join(5000); }
    }
}
