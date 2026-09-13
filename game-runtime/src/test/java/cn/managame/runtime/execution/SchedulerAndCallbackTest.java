package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.clock.GameClock;
import cn.managame.runtime.clock.MutableGameClock;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataKey;
import cn.managame.runtime.diagnostics.CallbackFailureException;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(15)
class SchedulerAndCallbackTest {
    static MutableGameClock clock() { return new MutableGameClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")); }

    @Test void timerWaitsOutsideRouteAndDispatchesOnlyAtDeadline() throws Exception {
        MutableGameClock clock = clock();
        try (GameRuntime runtime = builder().clock(clock).build()) {
            List<String> seen = new CopyOnWriteArrayList<>();
            TimerTask timer = runtime.schedule(TestRoutes.Player.class, 1, Duration.ofSeconds(5), () -> {
                assertEquals(new Route(TestRoutes.Player.class, 1), HandlerContexts.current().route()); seen.add("timer");
            });
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> seen.add("command")));
            assertEquals(List.of("command"), seen); assertEquals(TimerTask.State.SCHEDULED, timer.state());
            clock.advance(Duration.ofSeconds(4)); assertEquals(0, runtime.runDueTimers());
            clock.advance(Duration.ofSeconds(1)); assertEquals(1, runtime.runDueTimers());
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            assertEquals(List.of("command", "timer"), seen); assertEquals(TimerTask.State.DISPATCHED, timer.state());
            assertFalse(timer.cancel()); assertFalse(timer.isCancelled());
        }
    }

    @Test void cancelledTimerNeverDispatchesAndCloseCancelsPendingTimers() throws Exception {
        MutableGameClock clock = clock(); AtomicInteger count = new AtomicInteger();
        GameRuntime runtime = builder().clock(clock).build();
        TimerTask timer = runtime.schedule(TestRoutes.Player.class, 1, Duration.ofSeconds(1), count::incrementAndGet);
        TimerTask pending = runtime.schedule(TestRoutes.Player.class, 1, Duration.ofDays(1), count::incrementAndGet);
        assertTrue(timer.cancel()); assertFalse(timer.cancel()); assertTrue(timer.isCancelled());
        clock.advance(Duration.ofSeconds(2)); assertEquals(0, runtime.runDueTimers());
        done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {})); assertEquals(0, count.get());
        runtime.close(); assertTrue(pending.isCancelled());
        assertThrows(RejectedExecutionException.class, () -> runtime.schedule(TestRoutes.Player.class, 1, Duration.ZERO, () -> {}));
    }

    @Test void dispatchedTimerQueuesBehindBusyRouteAndCannotBeCancelled() throws Exception {
        MutableGameClock clock = clock();
        try (GameRuntime runtime = builder().clock(clock).build()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean fired = new AtomicBoolean();
            runtime.dispatch(TestRoutes.Player.class, 1, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                TimerTask timer = runtime.schedule(TestRoutes.Player.class, 1, Duration.ZERO, () -> fired.set(true));
                assertEquals(1, runtime.runDueTimers()); assertFalse(timer.cancel()); assertFalse(fired.get());
            } finally { release.countDown(); }
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {})); assertTrue(fired.get());
        }
    }

    @Test void zeroDelayNeverRunsInlineAndNegativeDelayIsRejected() throws Exception {
        MutableGameClock clock = clock();
        try (GameRuntime runtime = builder().clock(clock).build()) {
            List<String> seen = new CopyOnWriteArrayList<>();
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {
                runtime.schedule(TestRoutes.Player.class, 1, Duration.ZERO, () -> seen.add("timer"));
                runtime.runDueTimers(); seen.add("outer");
            }));
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            assertEquals(List.of("outer", "timer"), seen);
            assertThrows(IllegalArgumentException.class, () -> runtime.schedule(TestRoutes.Player.class, 1, Duration.ofSeconds(-1), () -> {}));
        }
    }

    static class CronHandler {
        final AtomicInteger fired = new AtomicInteger();
        final List<Instant> instants = new CopyOnWriteArrayList<>();
        final GameClock clock;
        CronHandler(GameClock clock) { this.clock = clock; }
        @Cron(value = "0 */5 * * * ?", routeType = TestRoutes.Guild.class, routeKey = 99)
        public void refresh() {
            assertTrue(Thread.currentThread().isVirtual());
            assertEquals(new Route(TestRoutes.Guild.class, 99), HandlerContexts.current().route());
            instants.add(clock.now()); fired.incrementAndGet();
        }
    }

    @Test void cronUsesGameClockAndCoalescesMissedFirings() throws Exception {
        MutableGameClock clock = clock(); CronHandler handler = new CronHandler(clock);
        try (GameRuntime runtime = builder().clock(clock).handler(handler).build()) {
            clock.advance(Duration.ofMinutes(4)); assertEquals(0, runtime.runDueTimers());
            clock.advance(Duration.ofMinutes(1)); assertEquals(1, runtime.runDueTimers());
            done(runtime.dispatch(TestRoutes.Guild.class, 99, () -> {})); assertEquals(1, handler.fired.get());
            clock.advance(Duration.ofDays(2)); assertEquals(1, runtime.runDueTimers());
            done(runtime.dispatch(TestRoutes.Guild.class, 99, () -> {})); assertEquals(2, handler.fired.get());
            assertEquals(clock.now(), handler.instants.getLast());
            assertEquals(0, runtime.runDueTimers());
        }
    }

    @Test void automaticSchedulerObservesClockChanges() {
        MutableGameClock clock = clock(); CountDownLatch fired = new CountDownLatch(1);
        try (GameRuntime runtime = builder().clock(clock).automaticScheduling(true).build()) {
            runtime.schedule(TestRoutes.Battle.class, 1, Duration.ofDays(1), fired::countDown);
            clock.advance(Duration.ofDays(1)); await(fired);
        }
    }

    @Test void cronParsesRangesNamesStepsAndRejectsUnsupportedOrImpossibleExpressions() {
        CronExpression daily = CronExpression.parse("0 0 0 * * ?");
        assertEquals(Instant.parse("2026-01-01T16:00:00Z"), daily.nextAfter(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        CronExpression weekday = CronExpression.parse("5,15 0/15 9-17 ? JAN MON-FRI");
        assertEquals(Instant.parse("2026-01-01T09:00:05Z"), weekday.nextAfter(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        assertEquals(Instant.parse("2028-02-29T00:00:00Z"), CronExpression.parse("0 0 0 29 FEB ?")
                .nextAfter(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        for (String value : List.of("invalid", "* * * * * *", "* * * ? * ?", "60 * * * * ?", "*/0 * * * * ?",
                "0 0 0 31 FEB ?", "0 0 0 L * ?", "0 0 0 ? * MON#2", "0 0 0 1 * ? 2026", "0 0 0 * 13 ?", "0 0 0 * * MON")) {
            assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(value), value);
        }
    }

    @Test void cronHandlesDaylightSavingGapsAndBothOverlapOccurrences() {
        ZoneId zone = ZoneId.of("America/New_York");
        CronExpression gap = CronExpression.parse("0 30 2 * * ?");
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), gap.nextAfter(Instant.parse("2026-03-08T05:00:00Z"), zone));
        CronExpression overlap = CronExpression.parse("0 30 1 * * ?");
        Instant first = overlap.nextAfter(Instant.parse("2026-11-01T04:00:00Z"), zone);
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first);
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), overlap.nextAfter(first, zone));
    }

    @Test void callbackCompletionReentersCapturedRouteWithDecodedBodyOnce() throws Exception {
        try (GameRuntime runtime = builder().build()) {
            AtomicReference<RuntimeCallback<String>> callback = new AtomicReference<>();
            AtomicReference<String> body = new AtomicReference<>();
            AtomicReference<Thread> executing = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            RouteTask outer = runtime.dispatch(TestRoutes.Player.class, 42, () -> {
                callback.set(runtime.callback(value -> {
                    assertEquals(new Route(TestRoutes.Player.class, 42), HandlerContexts.current().route());
                    executing.set(Thread.currentThread()); body.set(value);
                }));
                entered.countDown(); await(release);
            });
            try {
                await(entered);
                assertTrue(callback.get().onSuccess("decoded")); assertFalse(callback.get().onFail(TestErrorCodes.TIMEOUT));
                assertNull(body.get());
            } finally { release.countDown(); }
            done(outer); done(runtime.dispatch(TestRoutes.Player.class, 42, () -> {}));
            assertEquals("decoded", body.get()); assertNotSame(Thread.currentThread(), executing.get());
            assertTrue(callback.get().isCompleted());
        }
    }

    @Handler(routeType = TestRoutes.Player.class)
    static class CallbackCommand {
        GameRuntime runtime;
        RuntimeCallback<String> callback;
        java.util.function.Consumer<Throwable> onFail;
        @HandlerMethod public void run(BindingAndEventTest.UseItemReq request) { callback = runtime.callback(value -> {}, onFail); }
    }

    @Test void commandCallbackWithoutFailureHandlerUsesUnifiedFailureHandling() throws Exception {
        CallbackCommand command = new CallbackCommand();
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = BindingAndEventTest.commandBuilder(new AtomicInteger()).handler(command).exceptionHandler(errors::add).build()) {
            command.runtime = runtime;
            done(runtime.command(new CommandHandlerInvocation(new BindingAndEventTest.UseItemReq(1), 8L, Metadata.empty())));
            assertTrue(command.callback.onFail(TestErrorCodes.TIMEOUT));
            ExecutionException completion = assertThrows(ExecutionException.class, () -> done(command.callback.completion()));
            done(runtime.dispatch(TestRoutes.Player.class, 8, () -> {}));
            assertEquals(1, errors.size());
            assertSame(errors.getFirst(), completion.getCause());
            assertEquals(TestErrorCodes.TIMEOUT, assertInstanceOf(CallbackFailureException.class, errors.getFirst().getCause()).code());
            assertEquals(new Route(TestRoutes.Player.class, 8), errors.getFirst().context().route());
            assertEquals(HandlerContext.class, errors.getFirst().context().getClass());
        }
    }

    @Test void explicitFailureHandlerRunsOnCapturedRouteAndCompletesNormally() throws Exception {
        CallbackCommand command = new CallbackCommand(); AtomicInteger code = new AtomicInteger();
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        command.onFail = error -> {
            assertEquals(new Route(TestRoutes.Player.class, 8), HandlerContexts.current().route());
            assertEquals(HandlerContext.class, HandlerContexts.current().getClass());
            code.set(assertInstanceOf(CallbackFailureException.class, error).code());
        };
        try (GameRuntime runtime = BindingAndEventTest.commandBuilder(new AtomicInteger()).handler(command).exceptionHandler(errors::add).build()) {
            command.runtime = runtime;
            done(runtime.command(new CommandHandlerInvocation(new BindingAndEventTest.UseItemReq(1), 8L, Metadata.empty())));
            assertTrue(command.callback.onFail(1001));
            done(command.callback.completion());
            assertEquals(1001, code.get());
            assertTrue(errors.isEmpty());
        }
    }

    @Test void independentCallbackWithoutFailureHandlerUsesUnifiedFailureHandling() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = builder().exceptionHandler(errors::add).build()) {
            RuntimeCallback<String> callback = runtime.callback(new CallbackDefinition<>(new Route(TestRoutes.Guild.class, 5), value -> {}, null));
            callback.onFail(TestErrorCodes.UNAVAILABLE);
            ExecutionException completion = assertThrows(ExecutionException.class, () -> done(callback.completion()));
            done(runtime.dispatch(TestRoutes.Guild.class, 5, () -> {}));
            assertEquals(1, errors.size());
            assertSame(errors.getFirst(), completion.getCause());
            assertEquals(TestErrorCodes.UNAVAILABLE, assertInstanceOf(CallbackFailureException.class, errors.getFirst().getCause()).code());
        }
    }
    @Test void callbackCanOverrideBothRouteComponentsAndPropagatesMetadata() throws Exception {
        var key = MetadataKey.application(201, String.class);
        AtomicReference<RuntimeCallback<String>> callback = new AtomicReference<>();
        AtomicReference<HandlerContext> seen = new AtomicReference<>();
        try (GameRuntime runtime = builder().metadataPropagator(m -> m.with(key, "trace")).build()) {
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> callback.set(runtime.callback(
                    new CallbackDefinition<>(new Route(TestRoutes.Guild.class, -2), value -> seen.set(HandlerContexts.current()), null)))));
            callback.get().onSuccess("body");
            done(runtime.dispatch(TestRoutes.Guild.class, -2, () -> {}));
            assertEquals(new Route(TestRoutes.Guild.class, -2), seen.get().route()); assertEquals("trace", seen.get().metadata().get(key));
            assertThrows(IllegalStateException.class, () -> runtime.callback(value -> {}));
        }
    }

    @Test void timerAndCallbackExceptionsReleaseRoute() throws Exception {
        List<HandlerException> errors = new CopyOnWriteArrayList<>(); MutableGameClock clock = clock();
        try (GameRuntime runtime = builder().clock(clock).exceptionHandler(errors::add).build()) {
            runtime.schedule(TestRoutes.Guild.class, 2, Duration.ZERO, () -> { throw new IllegalStateException("timer"); });
            runtime.runDueTimers();
            runtime.callback(new CallbackDefinition<>(new Route(TestRoutes.Guild.class, 2), value -> { throw new IllegalArgumentException("callback"); }, null))
                    .onSuccess("body");
            done(runtime.dispatch(TestRoutes.Guild.class, 2, () -> {}));
            assertEquals(2, errors.size());
        }
    }
}
