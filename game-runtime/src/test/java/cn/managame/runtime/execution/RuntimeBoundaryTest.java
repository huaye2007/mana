package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.clock.MutableGameClock;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteKeyResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(15)
class RuntimeBoundaryTest {
    @Handler(routeType = TestRoutes.Player.class)
    static class PrimitiveHandler {
        long value;
        @HandlerMethod public void run(long key, BindingAndEventTest.UseItemReq request) {
            value = key; assertEquals(key, HandlerContexts.current().routeKey());
        }
    }

    @Test void primitiveSemanticParameterUsesBoxedResolverResultOnce() throws Exception {
        AtomicInteger resolved = new AtomicInteger(); PrimitiveHandler handler = new PrimitiveHandler();
        var parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(long.class, invocation -> {
                    resolved.incrementAndGet(); return (Long) invocation.connection();
                }).build();
        try (GameRuntime runtime = builder().protocols(BindingAndEventTest.protocols()).parameters(parameters)
                .defaultRoute(TestRoutes.Player.class, long.class, value -> value).handler(handler).build()) {
            done(runtime.command(new BindingAndEventTest.UseItemReq(1), Long.MIN_VALUE));
            assertEquals(Long.MIN_VALUE, handler.value); assertEquals(1, resolved.get());
        }
    }

    @Test void executingTaskExceptionObserverRetainsLogicalRouteAndRejectsSelfWait() throws Exception {
        AtomicReference<GameRuntime> reference = new AtomicReference<>(); AtomicBoolean observed = new AtomicBoolean();
        try (GameRuntime runtime = builder().exceptionHandler(error -> {
            assertSame(error.context(), HandlerContexts.current());
            RouteTask queued = reference.get().dispatch(TestRoutes.Guild.class, 9, () -> {});
            assertThrows(IllegalStateException.class, queued::join);
            assertThrows(IllegalStateException.class, reference.get()::close);
            observed.set(true);
        }).build()) {
            reference.set(runtime);
            assertThrows(ExecutionException.class, () -> done(runtime.dispatch(TestRoutes.Guild.class, 9, () -> {
                throw new IllegalStateException("business");
            })));
            done(runtime.dispatch(TestRoutes.Guild.class, 9, () -> {})); assertTrue(observed.get());
        }
    }

    @Test void reportingExternalFailureDoesNotCreateAnActiveRoute() {
        AtomicBoolean observed = new AtomicBoolean();
        try (GameRuntime runtime = builder().exceptionHandler(error -> {
            assertNull(HandlerContexts.currentOrNull()); observed.set(true);
        }).build()) {
            runtime.report("external", new HandlerContext(new Route(TestRoutes.Player.class, 1), Metadata.empty()),
                    new IllegalArgumentException());
            assertTrue(observed.get());
        }
    }

    static class BaseEvent {
        final long roleId;
        BaseEvent(long roleId) { this.roleId = roleId; }
    }
    static class DerivedEvent extends BaseEvent { DerivedEvent(long roleId) { super(roleId); } }
    static class BaseRoute implements RouteKeyResolver<BaseEvent> {
        public long resolve(BaseEvent event) { return event.roleId; }
    }
    @EventHandler(routeType = TestRoutes.Player.class, routeKeyResolver = BaseRoute.class)
    static class BaseSourceHandler {
        long key;
        @EventMethod public void onDerived(DerivedEvent event) { key = HandlerContexts.current().routeKey(); }
    }

    @Test void eventCanUseBusinessSuperclassAsRouteSource() throws Exception {
        BaseSourceHandler handler = new BaseSourceHandler();
        try (GameRuntime runtime = builder().eventType(DerivedEvent.class).routeKeyResolver(BaseEvent.class, new BaseRoute())
                .handler(handler).build()) {
            runtime.publish(new DerivedEvent(-7));
            done(runtime.dispatch(TestRoutes.Player.class, -7, () -> {})); assertEquals(-7, handler.key);
        }
    }

    @Test void equalRouteInAnotherRuntimeStillQueuesEvent() throws Exception {
        BaseSourceHandler handler = new BaseSourceHandler();
        try (GameRuntime a = builder().build(); GameRuntime b = builder().eventType(DerivedEvent.class)
                .routeKeyResolver(BaseEvent.class, new BaseRoute()).handler(handler).build()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            b.dispatch(TestRoutes.Player.class, 7, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                done(a.dispatch(TestRoutes.Player.class, 7, () -> b.publish(new DerivedEvent(7))));
                assertEquals(0, handler.key);
            } finally { release.countDown(); }
            done(b.dispatch(TestRoutes.Player.class, 7, () -> {})); assertEquals(7, handler.key);
        }
    }

    static class FailingCron {
        final AtomicInteger calls = new AtomicInteger();
        @Cron(value = "* * * * * ?", routeType = TestRoutes.Battle.class, routeKey = 8)
        public void tick() { calls.incrementAndGet(); throw new IllegalStateException("tick"); }
    }

    @Test void cronContinuesAfterFailureAndBackwardClockDoesNotReplayIt() throws Exception {
        MutableGameClock clock = SchedulerAndCallbackTest.clock(); FailingCron cron = new FailingCron();
        List<HandlerException> failures = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = builder().clock(clock).handler(cron).exceptionHandler(failures::add).build()) {
            clock.advance(Duration.ofSeconds(1)); runtime.runDueTimers();
            done(runtime.dispatch(TestRoutes.Battle.class, 8, () -> {}));
            clock.advance(Duration.ofSeconds(-1)); assertEquals(0, runtime.runDueTimers());
            clock.advance(Duration.ofSeconds(1)); assertEquals(0, runtime.runDueTimers());
            clock.advance(Duration.ofSeconds(1)); assertEquals(1, runtime.runDueTimers());
            done(runtime.dispatch(TestRoutes.Battle.class, 8, () -> {}));
            assertEquals(2, cron.calls.get()); assertEquals(2, failures.size());
        }
    }

    @Test void callbackSuccessAndFailureRaceDeliversExactlyOneBusinessCompletion() throws Exception {
        try (GameRuntime runtime = builder().build(); ExecutorService callers = Executors.newFixedThreadPool(2)) {
            AtomicInteger completions = new AtomicInteger();
            for (int i = 0; i < 100; i++) {
                RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(new Route(TestRoutes.Account.class, 1),
                        value -> completions.incrementAndGet(), code -> completions.incrementAndGet()));
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> success = callers.submit(() -> { await(start); return callback.onSuccess("body"); });
                Future<Boolean> failure = callers.submit(() -> { await(start); return callback.onFail(TestErrorCodes.TIMEOUT); });
                start.countDown(); assertNotEquals(success.get(), failure.get());
            }
            done(runtime.dispatch(TestRoutes.Account.class, 1, () -> {})); assertEquals(100, completions.get());
        }
    }

    @Test void timerCancelAndDispatchRaceMatchesReportedState() throws Exception {
        MutableGameClock clock = SchedulerAndCallbackTest.clock();
        try (GameRuntime runtime = builder().clock(clock).build(); ExecutorService callers = Executors.newFixedThreadPool(2)) {
            AtomicInteger completions = new AtomicInteger(); int expected = 0;
            for (int i = 0; i < 100; i++) {
                TimerTask timer = runtime.schedule(TestRoutes.Guild.class, 1, Duration.ZERO, completions::incrementAndGet);
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> cancelled = callers.submit(() -> { await(start); return timer.cancel(); });
                Future<Integer> dispatched = callers.submit(() -> { await(start); return runtime.runDueTimers(); });
                start.countDown(); boolean didCancel = cancelled.get(); dispatched.get();
                if (didCancel) assertEquals(TimerTask.State.CANCELLED, timer.state());
                else { assertEquals(TimerTask.State.DISPATCHED, timer.state()); expected++; }
            }
            done(runtime.dispatch(TestRoutes.Guild.class, 1, () -> {})); assertEquals(expected, completions.get());
        }
    }

    @Test void completeExampleRuns() { RuntimeExample.main(new String[0]); }
}
