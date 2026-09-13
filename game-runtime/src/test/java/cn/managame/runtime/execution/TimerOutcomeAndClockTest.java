package cn.managame.runtime.execution;

import cn.managame.runtime.clock.MutableGameClock;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TimerOutcomeAndClockTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 1);
    private static MutableGameClock clock() {
        return new MutableGameClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
    }
    private static GameRuntime.Builder builder(MutableGameClock clock, List<HandlerException> errors) {
        return GameRuntime.builder().clock(clock).automaticScheduling(false).exceptionHandler(errors::add)
                .executionDomain(PLAYER.type(), ExecutionDomain.platform("timer-test").threads(1)
                        .maxTasks(8).maxTasksPerRoute(8).build());
    }
    private static void done(RouteTask task) throws Exception { task.get(3, TimeUnit.SECONDS); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test void rejectedTimerHasTerminalOutcomeAndDoesNotExecuteLater() throws Exception {
        var clock = clock();
        var errors = new CopyOnWriteArrayList<HandlerException>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fired = new AtomicBoolean();
        try (var runtime = GameRuntime.builder().clock(clock).automaticScheduling(false).exceptionHandler(errors::add)
                .executionDomain(PLAYER.type(), ExecutionDomain.platform("full").threads(1)
                        .maxTasks(1).maxTasksPerRoute(1).callbackReserve(0, 0).build()).build()) {
            RouteTask blocker = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                TimerTask timer = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ZERO, () -> fired.set(true));
                RouteTask completion = timer.completion();
                assertEquals(1, runtime.runDueTimers());
                assertEquals(TimerTask.State.REJECTED, timer.state());
                assertSame(completion, timer.completion());
                assertFalse(timer.cancel());
                ExecutionException failure = assertThrows(ExecutionException.class, () -> done(completion));
                assertInstanceOf(RuntimeOverloadedException.class, failure.getCause().getCause());
                assertEquals(List.of(failure.getCause()), errors);
                assertEquals(0, runtime.metrics().scheduledTimers());
            } finally { release.countDown(); }
            done(blocker);
            done(runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> {}));
            assertFalse(fired.get());
        }
    }

    @Test void timerCompletionTracksBusinessAndNotJustSubmission() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<HandlerException>();
        try (var runtime = builder(clock(), errors).build()) {
            TimerTask timer = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ZERO,
                    () -> { entered.countDown(); await(release); });
            try {
                runtime.runDueTimers(); await(entered);
                assertEquals(TimerTask.State.DISPATCHED, timer.state());
                assertFalse(timer.completion().isDone());
            } finally { release.countDown(); }
            done(timer.completion());
            var cause = new IllegalStateException("business");
            TimerTask failed = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ZERO, () -> { throw cause; });
            runtime.runDueTimers();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> done(failed.completion()));
            assertSame(cause, failure.getCause().getCause());
            assertEquals(TimerTask.State.DISPATCHED, failed.state());
        }
    }

    @Test void cancellationAndShutdownCompleteWaitingTimers() throws Exception {
        var runtime = builder(clock(), new CopyOnWriteArrayList<>()).build();
        try {
            TimerTask cancelled = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ofDays(1), () -> fail("cancelled"));
            var notification = cancelled.completion().onComplete(PLAYER,
                    failure -> assertInstanceOf(CancellationException.class, failure));
            assertTrue(cancelled.cancel());
            assertFalse(cancelled.cancel());
            assertTrue(cancelled.completion().isCancelled());
            assertThrows(CancellationException.class, () -> done(cancelled.completion()));
            done(notification);
            TimerTask pending = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ofDays(1), () -> fail("closed"));
            assertTrue(runtime.close(Duration.ofSeconds(3)).terminated());
            assertEquals(TimerTask.State.CANCELLED, pending.state());
            assertThrows(CancellationException.class, () -> done(pending.completion()));
        } finally { runtime.close(); }
    }

    @Test void calendarJumpsDoNotMoveRelativeDeadlines() throws Exception {
        var clock = clock();
        var seen = new CopyOnWriteArrayList<String>();
        try (var runtime = builder(clock, new CopyOnWriteArrayList<>()).build()) {
            TimerTask relative = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ofSeconds(5), () -> seen.add("elapsed"));
            TimerTask calendar = runtime.scheduleAt(PLAYER.type(), PLAYER.key(), clock.now().plusSeconds(10), () -> seen.add("calendar"));
            assertTrue(relative.isRelative()); assertFalse(calendar.isRelative());
            clock.setTime(clock.now().plus(Duration.ofDays(100)));
            assertEquals(1, runtime.runDueTimers()); done(calendar.completion());
            assertFalse(relative.completion().isDone());
            clock.setTime(clock.now().minus(Duration.ofDays(200)));
            assertEquals(0, runtime.runDueTimers());
            clock.advanceElapsed(Duration.ofSeconds(4));
            assertEquals(0, runtime.runDueTimers());
            clock.advanceElapsed(Duration.ofSeconds(1));
            assertEquals(1, runtime.runDueTimers()); done(relative.completion());
            assertEquals(List.of("calendar", "elapsed"), seen);
        }
    }

    @Test void bothClockQueuesRespectBudgetCancellationAndOrdering() throws Exception {
        var clock = clock();
        var seen = new CopyOnWriteArrayList<Integer>();
        try (var runtime = builder(clock, new CopyOnWriteArrayList<>()).timerOptions(new TimerOptions(3, 1)).build()) {
            TimerTask first = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ofSeconds(1), () -> seen.add(1));
            TimerTask second = runtime.scheduleAt(PLAYER.type(), PLAYER.key(), clock.now().plusSeconds(2), () -> seen.add(2));
            TimerTask cancelled = runtime.schedule(PLAYER.type(), PLAYER.key(), Duration.ofSeconds(2), () -> seen.add(99));
            assertThrows(RuntimeOverloadedException.class,
                    () -> runtime.scheduleAt(PLAYER.type(), 1, clock.now(), () -> {}));
            assertTrue(cancelled.cancel());
            clock.advance(Duration.ofSeconds(2));
            assertEquals(1, runtime.runDueTimers()); done(first.completion());
            assertEquals(1, runtime.runDueTimers()); done(second.completion());
            assertEquals(0, runtime.runDueTimers());
            assertEquals(List.of(1, 2), seen);
            assertEquals(0, runtime.metrics().scheduledTimers());
        }
    }
}
