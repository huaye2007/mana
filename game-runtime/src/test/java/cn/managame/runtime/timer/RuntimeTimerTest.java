package cn.managame.runtime.timer;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Per-Route timer cancellation: a Route whose business ends drops its timers in one call. */
class RuntimeTimerTest {
    private static final Duration NEVER = Duration.ofDays(1);

    private final List<RuntimeError> errors = new ArrayList<>();
    private GameRuntime runtime;

    @AfterEach void close() {
        if (runtime != null) runtime.close();
        GameTime.resetClock();
    }

    private GameRuntime runtime(Object... crons) {
        runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player"), RouteDomain.of(2, "guild")))
                .routeExecutors(List.of(RouteExecutorBinding.of((d, k, task) -> {
                    task.run(); return RouteExecuteStatus.ACCEPTED;
                }, 1, 2)))
                .cronHandlers(List.of(crons)).errorHandler(errors::add).build();
        return runtime;
    }

    @Test void cancelAllClaimsOneRoutesTimersAndLeavesEveryOtherRouteAlone() {
        var runtime = runtime();
        runtime.timer().schedule(1, 7, NEVER, () -> fail("cancelled"));
        runtime.timer().schedule(1, 7, NEVER, () -> fail("cancelled"));
        runtime.timer().schedule(1, 7, NEVER, () -> fail("cancelled"));
        TimerRef otherKey = runtime.timer().schedule(1, 8, NEVER, () -> fail("other Route"));
        TimerRef otherDomain = runtime.timer().schedule(2, 7, NEVER, () -> fail("other Route"));

        assertEquals(3, runtime.timer().cancelAll(1, 7));
        // The Key alone never isolates: neither neighbour was touched, and both still cancel themselves.
        assertTrue(otherKey.cancel());
        assertTrue(otherDomain.cancel());
        assertTrue(errors.isEmpty());
    }

    @Test void aTimerThatAlreadyFiredIsNeitherCountedNorRetracted() throws Exception {
        var runtime = runtime();
        var fired = new CountDownLatch(1);
        TimerRef timer = runtime.timer().schedule(1, 7, Duration.ZERO, fired::countDown);
        assertTrue(fired.await(5, TimeUnit.SECONDS));

        assertEquals(0, runtime.timer().cancelAll(1, 7));
        assertFalse(timer.cancel());
        assertTrue(errors.isEmpty());
    }

    @Test void anIndividuallyCancelledTimerLeavesNoStaleEntryBehind() {
        var runtime = runtime();
        TimerRef first = runtime.timer().schedule(1, 7, NEVER, () -> fail("cancelled"));
        runtime.timer().schedule(1, 7, NEVER, () -> fail("cancelled"));

        assertTrue(first.cancel());
        // Only the survivor is still pending; the cancelled one is gone from the Route's index.
        assertEquals(1, runtime.timer().cancelAll(1, 7));
        assertEquals(0, runtime.timer().cancelAll(1, 7));
    }

    @Test void aQuietRouteCostsNothingToCancel() {
        var runtime = runtime();
        assertEquals(0, runtime.timer().cancelAll(1, 7));
        assertEquals(0, runtime.timer().cancelAll(99, 1));
    }

    static class Job {
        final AtomicInteger calls = new AtomicInteger();
        @Cron(value = "0 * * * * ?", domain = 1, routeKey = 7)
        public void tick() { calls.incrementAndGet(); }
    }

    @Test @ResourceLock("GameTime") void cancelAllLeavesCronAlone() throws Exception {
        // A Cron registers its next cycle only after the current one runs, so cancelling its pending
        // timer here would stop the schedule for good. Its timers are deliberately not indexed.
        GameTime.setClock(Clock.fixed(Instant.parse("2026-09-25T00:00:59.990Z"), ZoneOffset.UTC));
        var job = new Job();
        var runtime = runtime(job);

        assertEquals(0, runtime.timer().cancelAll(1, 7));
        for (int i = 0; i < 100 && job.calls.get() == 0; i++) Thread.sleep(20);
        assertTrue(job.calls.get() > 0, "Cron must still fire after cancelAll on its Route");
    }
}
