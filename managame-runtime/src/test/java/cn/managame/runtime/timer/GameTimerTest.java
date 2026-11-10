package cn.managame.runtime.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.RequestContext;
import cn.managame.runtime.TaskInterceptor;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.time.GameTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameTimerTest {
    private static final int PLAYER = 1, WORLD = 2;
    private static final byte PLAYER_ID = 1;
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);

    private record Request(int domain, long routeKey, byte businessType, long businessId, Metadata metadata)
            implements RequestContext {
        @Override public boolean reply(Object result) { return true; }
        @Override public boolean fail(int errorCode) { return true; }
    }

    private GameRuntime runtime;
    private GameTimer timer;

    @BeforeEach void start() {
        runtime = GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads())
                .domain(WORLD, RouteExecutors.striped(1)).build();
        timer = new GameTimer(runtime);
    }

    @AfterEach void stop() {
        timer.close();
        runtime.close();
    }

    @Test void runsOnceAfterTheDelayWithItsContext() throws Exception {
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        GameContext world = GameContext.of(WORLD, 0);
        long start = System.nanoTime();
        Scheduled scheduled = timer.after(world, Duration.ofMillis(100), context -> seen.add(Context.current()));
        assertSame(world, seen.poll(5, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100));
        assertTrue(scheduled.isDone());
        assertNull(seen.poll(200, TimeUnit.MILLISECONDS), "a one-shot timer fires once");
    }

    @Test void withoutAContextItUsesAPlainCopyOfTheCallingTasksContext() throws Exception {
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        Request request = new Request(PLAYER, 1001, PLAYER_ID, 1001, Metadata.builder().put(TRACE, "t1").build());
        runtime.execute(request, context -> timer.after(Duration.ofMillis(10), later -> seen.add(Context.current())));
        Context fired = (Context) seen.poll(5, TimeUnit.SECONDS);
        assertEquals(GameContext.copyOf(request), fired);
        assertFalse(fired instanceof RequestContext, "a timer cannot answer the request that set it");
        assertThrows(IllegalStateException.class, () -> timer.after(Duration.ofMillis(10), context -> {}),
                "outside a task there is no context to take");
    }

    @Test void periodicTimersRepeatUntilCancelled() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        Scheduled scheduled = timer.every(GameContext.of(WORLD, 0), Duration.ofMillis(20), context -> {
            fired.incrementAndGet();
            three.countDown();
        });
        assertTrue(three.await(5, TimeUnit.SECONDS));
        assertTrue(scheduled.cancel());
        assertFalse(scheduled.cancel());
        TimeUnit.MILLISECONDS.sleep(50); // a firing handed over just before cancel may still run
        int afterCancel = fired.get();
        TimeUnit.MILLISECONDS.sleep(100);
        assertEquals(afterCancel, fired.get());
        assertTrue(scheduled.isDone());
    }

    @Test void aCancelledTimerNeverFires() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        Scheduled scheduled = timer.after(GameContext.of(WORLD, 0), Duration.ofMillis(100), context -> fired.incrementAndGet());
        assertTrue(scheduled.cancel());
        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(0, fired.get());
    }

    @Test void aTimerCancelledOnItsRouteRunsNoMore() throws Exception {
        GameContext route = GameContext.of(PLAYER, 7);
        AtomicInteger fired = new AtomicInteger();
        List<Boolean> stopped = new CopyOnWriteArrayList<>();
        CountDownLatch behind = new CountDownLatch(1);
        runtime.execute(route, context -> {
            Scheduled tick = timer.every(route, Duration.ofMillis(20), later -> fired.incrementAndGet());
            Scheduled expiry = timer.after(route, Duration.ofMillis(20), later -> fired.incrementAndGet());
            TimeUnit.MILLISECONDS.sleep(100);   // both are due, and wait for this task behind it
            stopped.addAll(List.of(tick.cancel(), expiry.cancel(), expiry.isDone()));
            runtime.execute(route, later -> behind.countDown());
        });
        assertTrue(behind.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(true, true, true), stopped);
        assertEquals(0, fired.get(), "what waited on the route found its timer cancelled");
    }

    @Test void aPeriodicTaskSlowerThanItsPeriodSkipsFiringsRatherThanFillItsRoute() throws Exception {
        RouteExecutor executor = RouteExecutors.virtualThreads();
        AtomicInteger ran = new AtomicInteger();
        long most = 0;
        try (GameRuntime slow = GameRuntime.builder().domain(PLAYER, executor).build(); GameTimer fast = new GameTimer(slow, Duration.ofMillis(1))) {
            fast.every(GameContext.of(PLAYER, 7), Duration.ofMillis(5), context -> {
                ran.incrementAndGet();
                TimeUnit.MILLISECONDS.sleep(25);
            });
            for (int i = 0; i < 40; i++) {
                most = Math.max(most, executor.pending());
                TimeUnit.MILLISECONDS.sleep(5);
            }
        }
        assertTrue(most <= 2, "the running one, and the next as it ends; saw " + most);
        assertTrue(ran.get() >= 2, "it goes on as each firing finishes");
    }

    @Test void aCronTaskStillRunningSkipsItsNextTimes() throws Exception {
        RouteExecutor executor = RouteExecutors.virtualThreads();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        long pending;
        try (GameRuntime slow = GameRuntime.builder().domain(PLAYER, executor).build(); GameTimer fast = new GameTimer(slow, Duration.ofMillis(1))) {
            fast.cron(GameContext.of(PLAYER, 7), "* * * * * *", context -> {
                started.countDown();
                release.await();
            });
            started.await(3, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(2200);   // two more seconds come round
            pending = executor.pending();
            release.countDown();
        }
        assertEquals(1, pending, "the running one alone");
    }

    @Test void aPeriodicTimerGoesOnAfterAnInterceptorRefusesAFiring() throws Exception {
        AtomicInteger befores = new AtomicInteger();
        TaskInterceptor gate = new TaskInterceptor() {
            @Override public boolean before(Context context) { return befores.incrementAndGet() > 3; }
        };
        CountDownLatch fired = new CountDownLatch(1);
        try (GameRuntime gated = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).intercept(List.of(gate)).build();
             GameTimer fast = new GameTimer(gated, Duration.ofMillis(1))) {
            fast.every(GameContext.of(PLAYER, 7), Duration.ofMillis(5), context -> fired.countDown());
            assertTrue(fired.await(5, TimeUnit.SECONDS));
        }
    }

    @Test void aOneShotTimerDueOnAFullRouteStillRuns() throws Exception {
        GameContext route = GameContext.of(PLAYER, 7);
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        Scheduled expiry;
        try (GameRuntime full = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads(1, Duration.ofSeconds(60))).build();
             GameTimer fast = new GameTimer(full, Duration.ofMillis(1))) {
            full.execute(route, context -> release.await());
            full.execute(route, context -> {});
            assertThrows(GameRuntimeException.class, () -> full.execute(route, context -> {}), "the route takes no more from outside");
            expiry = fast.after(route, Duration.ofMillis(5), context -> fired.incrementAndGet());
            TimeUnit.MILLISECONDS.sleep(100);   // due meanwhile, with the route still full
            release.countDown();
            for (int i = 0; i < 500 && fired.get() == 0; i++) TimeUnit.MILLISECONDS.sleep(10);
        }
        assertEquals(1, fired.get(), "what a timer was set for happens, however full its route");
        assertTrue(expiry.isDone());
    }

    @Test void aOneShotTimerDueAfterTheRuntimeShutDownNeverRuns() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        Scheduled expiry = timer.after(GameContext.of(WORLD, 0), Duration.ofMillis(50), context -> fired.incrementAndGet());
        runtime.close();
        for (int i = 0; i < 100 && !expiry.isDone(); i++) TimeUnit.MILLISECONDS.sleep(10);
        assertTrue(expiry.isDone(), "the timer ended");
        assertEquals(0, fired.get());
    }

    @Test void aTimerSetAfterCloseIsCancelledAndNeverFires() throws Exception {
        GameContext world = GameContext.of(WORLD, 0);
        AtomicInteger fired = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch set = new CountDownLatch(1);
        try (GameRuntime closing = GameRuntime.builder().domain(WORLD, RouteExecutors.striped(1))
                .errorHandler((context, cause) -> errors.add(cause)).build()) {
            GameTimer closed = new GameTimer(closing, Duration.ofMillis(1));
            closed.close();
            List<Scheduled> late = new CopyOnWriteArrayList<>();
            // A task still finishing while the application shuts down, after its timers were closed.
            closing.execute(world, context -> {
                late.add(closed.after(Duration.ofMillis(5), later -> fired.incrementAndGet()));
                late.add(closed.every(Duration.ofMillis(5), later -> fired.incrementAndGet()));
                late.add(closed.cron(world, "* * * * * *", later -> fired.incrementAndGet()));
                set.countDown();
            });
            assertTrue(set.await(5, TimeUnit.SECONDS), "the task was not failed by a closed timer: " + errors);
            for (Scheduled scheduled : late) {
                assertTrue(scheduled.isDone());
                assertFalse(scheduled.cancel());
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertEquals(List.of(), errors);
        assertEquals(0, fired.get());
    }

    @Test void periodicTimersStopWhenTheRuntimeShutsDown() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        Scheduled scheduled = timer.every(GameContext.of(WORLD, 0), Duration.ofMillis(10), context -> {
            fired.incrementAndGet();
            first.countDown();
        });
        assertTrue(first.await(5, TimeUnit.SECONDS));
        runtime.close();
        for (int i = 0; i < 100 && !scheduled.isDone(); i++) TimeUnit.MILLISECONDS.sleep(10);
        assertTrue(scheduled.isDone(), "the timer stopped itself");
    }

    @Test void aTimerPerPlayerScales() throws Exception {
        int players = 100_000;
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch kept = new CountDownLatch(players / 2);
        Scheduled[] timers = new Scheduled[players];
        for (int id = 0; id < players; id++)
            // Half a second at least, so that adding and cancelling them all ends before any fires, even on a busy machine.
            timers[id] = timer.after(GameContext.of(PLAYER, id), Duration.ofMillis(500 + id % 50), context -> {
                fired.incrementAndGet();
                kept.countDown();
            });
        for (int id = 1; id < players; id += 2) assertTrue(timers[id].cancel());
        assertTrue(kept.await(10, TimeUnit.SECONDS));
        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(players / 2, fired.get(), "cancelled timers never fire");
    }

    @Test void closingTheTimerCancelsWhatIsPending() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        Scheduled scheduled = timer.after(GameContext.of(WORLD, 0), Duration.ofMillis(100), context -> fired.incrementAndGet());
        timer.close();
        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(0, fired.get());
        assertTrue(scheduled.isDone());
    }

    class DailyJobs {
        final BlockingQueue<String> ran = new LinkedBlockingQueue<>();

        @Cron(domain = WORLD, routeKey = 7, value = "* * * * * *")
        void tick(GameContext context) { ran.add("tick on " + Context.current().routeKey() + " as " + context.routeKey()); }

        @Cron(domain = WORLD, value = "* * * * * *")
        String report() { return ran.add("report") ? "ignored" : null; }
    }

    @Test void cronTimersFireWhenTheirExpressionMatchesOnTheirRoute() throws Exception {
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        GameContext world = GameContext.of(WORLD, 0);
        Scheduled everySecond = timer.cron(world, "* * * * * *", context -> seen.add(Context.current()));
        assertSame(world, seen.poll(3, TimeUnit.SECONDS));
        assertSame(world, seen.poll(3, TimeUnit.SECONDS), "and again the next second");
        assertTrue(everySecond.cancel());
        assertTrue(everySecond.isDone());
        assertThrows(IllegalArgumentException.class, () -> timer.cron(world, "0 0 5 * *", context -> {}));
        assertThrows(IllegalArgumentException.class, () -> timer.cron(GameContext.of(99, 0), "* * * * * *", context -> {}));
    }

    /** A running clock that now shows {@code time} in Shanghai, in {@code zone}. */
    private static Clock clockAt(String time, ZoneId zone) {
        Instant then = LocalDateTime.parse(time).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        return Clock.offset(Clock.system(zone), Duration.between(Instant.now(), then));
    }

    @Test void cronTimersFollowGameTimesClockAndZone() throws Exception {
        Clock original = GameTime.clock();
        BlockingQueue<String> fired = new LinkedBlockingQueue<>();
        try {
            GameTime.setClock(clockAt("2026-11-01T04:59:58", ZoneOffset.UTC));   // 20:59:58 in the game's zone
            timer.cron(GameContext.of(WORLD, 0), "0 0 5 * * *", context -> fired.add("five"));
            GameTime.setZone(ZoneId.of("Asia/Shanghai"));
            assertEquals("five", fired.poll(4, TimeUnit.SECONDS), "rescheduled for five in Shanghai, two seconds away");
            GameTime.setClock(clockAt("2026-11-01T04:59:58", ZoneId.of("Asia/Shanghai")));
            assertEquals("five", fired.poll(4, TimeUnit.SECONDS), "the clock set back before five: five again");
        } finally {
            GameTime.setClock(original);
        }
    }

    @Test void registerSchedulesEveryCronMethod() throws Exception {
        DailyJobs jobs = new DailyJobs();
        List<Scheduled> scheduled = timer.register(List.of(jobs));
        assertEquals(2, scheduled.size());
        Set<String> ran = new HashSet<>();
        while (ran.size() < 2) ran.add(jobs.ran.poll(3, TimeUnit.SECONDS));
        assertEquals(Set.of("tick on 7 as 7", "report"), ran);
        for (Scheduled job : scheduled) assertTrue(job.cancel());
    }

    @Test void registerRefusesBadCronMethodsAndThenSchedulesNothing() throws Exception {
        AtomicInteger good = new AtomicInteger();
        class Broken {
            @Cron(domain = WORLD, value = "* * * * * *") void aGoodOne() { good.incrementAndGet(); }
            @Cron(domain = WORLD, value = "* * * * *") void zBadExpression() {}
        }
        class TakesAMessage { @Cron(domain = WORLD, value = "* * * * * *") void run(String message) {} }
        class UnknownDomain { @Cron(domain = 99, value = "* * * * * *") void run() {} }
        for (Object bad : List.of(new Broken(), new TakesAMessage(), new UnknownDomain()))
            assertThrows(IllegalArgumentException.class, () -> timer.register(List.of(bad)), bad.getClass().getSimpleName());
        TimeUnit.MILLISECONDS.sleep(1500);
        assertEquals(0, good.get(), "the good method of a refused object was not scheduled");
    }
}
