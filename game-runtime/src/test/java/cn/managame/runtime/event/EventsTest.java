package cn.managame.runtime.event;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import cn.managame.core.Metadata;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
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
    // Closing no longer releases the binding, so the test harness clears what a process never would.
    @AfterEach void close() { runtimes.forEach(GameRuntime::close); Events.resetForTests(); }

    private GameRuntime runtime(Listener listener) {
        var runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "events")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1)))
                .eventHandlers(List.of(listener)).handlers(List.of(new Requests()))
                .protocols(ProtocolRegistry.of(
                    ProtocolDescriptor.request(1, Request.class, Response.class),
                    ProtocolDescriptor.response(1, Response.class))).build();
        runtimes.add(runtime);
        return runtime;
    }

    @Test void missingRuntimeAndNullArgumentsAreRejected() {
        assertThrows(IllegalStateException.class, () -> Events.publish(new Notice(1, 7)));
        assertThrows(NullPointerException.class, () -> Events.publish(null));
        assertThrows(NullPointerException.class, () -> Events.bind(null));
    }

    @Test void externalPublicationUsesTheDefaultAndKeepsItAfterClose() throws Exception {
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
        // The binding outlives the Runtime it names, so a publisher learns it is closed rather than
        // finding no default at all.
        assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                () -> Events.publish(event)).errorCode());
    }

    @Test void currentRuntimeWinsAndSameRouteInlinesWithIdentityAndContextRestoration() throws Exception {
        var ownListener = new Listener(); var otherListener = new Listener();
        var owner = runtime(ownListener); var other = runtime(otherListener); Events.bind(other);
        var result = new DefaultPromise<Seen>(GlobalEventExecutor.INSTANCE);
        var restored = new DefaultPromise<Context>(GlobalEventExecutor.INSTANCE);
        var source = new DefaultHandlerContext(1, 7, 4, 99, Metadata.empty(), new Request(() -> {
            try {
                Events.publish(new Notice(1, 7));
                result.trySuccess(ownListener.seen.poll());
                restored.trySuccess(Contexts.current());
            } catch (Throwable error) { result.tryFailure(error); restored.tryFailure(error); }
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

    @Test void theBindingIsFixedForTheProcess() throws Exception {
        var listener = new Listener(); var first = runtime(listener); var second = runtime(new Listener());
        Events.bind(first); Events.bind(first);
        assertThrows(IllegalStateException.class, () -> Events.bind(second));
        Events.publish(new Notice(1, 7));
        assertNotNull(listener.seen.poll(5, TimeUnit.SECONDS));

        first.close();
        // Closing releases nothing, so no replacement can take the bound Runtime's place afterwards.
        assertThrows(IllegalStateException.class, () -> Events.bind(second));
        assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                () -> Events.publish(new Notice(1, 7))).errorCode());
    }

    @Test void closedCurrentRuntimeDoesNotFallBackToAnOpenDefault() throws Exception {
        var owner = runtime(new Listener()); var otherListener = new Listener(); Events.bind(runtime(otherListener));
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var outcome = new DefaultPromise<Throwable>(GlobalEventExecutor.INSTANCE);
        owner.dispatch(new DefaultHandlerContext(1, 7, new Request(() -> {
            entered.countDown();
            try { proceed.await(); Events.publish(new Notice(1, 7)); outcome.trySuccess(null); }
            catch (Throwable error) { outcome.trySuccess(error); }
        })));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); owner.close(); proceed.countDown();
            var failure = assertInstanceOf(RuntimeDispatchException.class, outcome.get(5, TimeUnit.SECONDS));
            assertEquals(RUNTIME_CLOSED, failure.errorCode()); assertNull(otherListener.seen.poll());
        } finally { proceed.countDown(); }
    }

    @Test void aPublicationThatSelectedTheDefaultObservesItsClosure() throws Exception {
        var firstListener = new Listener(); var first = runtime(firstListener);
        var selected = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var gated = (GameRuntime) Proxy.newProxyInstance(GameRuntime.class.getClassLoader(),
                new Class[]{GameRuntime.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "eventBus" -> {
                        selected.countDown();
                        if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Publication stalled");
                        yield first.eventBus();
                    }
                    case "close" -> { first.close(); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        runtimes.add(gated); Events.bind(gated);
        var done = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);
        Thread publisher = Thread.ofPlatform().start(() -> {
            try { Events.publish(new Notice(1, 7)); done.trySuccess(null); }
            catch (Throwable error) { done.tryFailure(error); }
        });
        try {
            assertTrue(selected.await(5, TimeUnit.SECONDS));
            // The binding cannot be withdrawn, so this publication can only reach the Runtime it already
            // selected. Closing that Runtime meanwhile makes it fail there rather than succeed elsewhere.
            first.close(); proceed.countDown();
            var failure = assertInstanceOf(RuntimeDispatchException.class,
                    assertThrows(ExecutionException.class, () -> done.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(RUNTIME_CLOSED, failure.errorCode());
            assertNull(firstListener.seen.poll());
        } finally { proceed.countDown(); publisher.join(5000); }
    }
}
