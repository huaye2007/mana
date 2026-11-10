package cn.managame.runtime.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameTask;
import cn.managame.runtime.executor.RouteExecutors;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimerGroupTest {
    private static final int PLAYER = 1;
    private static final byte PLAYER_ID = 1;

    /** A buff's expiry, as a game would key it. */
    private record BuffExpiry(int buffId) {}

    /** What a player's in-memory object keeps: its timers, by what they are for. */
    private final GameContext player = GameContext.of(PLAYER, 1001, PLAYER_ID, 1001);
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final Map<String, LongAdder> calls = new ConcurrentHashMap<>();
    private GameRuntime runtime;
    private GameTimer timer;
    private TimerGroup timers;

    /** Counts the tasks run by handler. */
    private final class Backend implements Metrics {
        @Override public Counter counter(String name, String... tags) {
            if (!name.equals("managame.runtime.handler.calls")) return amount -> {};
            return calls.computeIfAbsent(tags[1], handler -> new LongAdder())::add;
        }

        @Override public Meter counter(String name, LongSupplier count, String... tags) { return () -> {}; }

        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) { return () -> {}; }
    }

    @BeforeEach void start() {
        runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads())
                .errorHandler((context, cause) -> errors.add(cause)).metrics(new Backend()).build();
        timer = new GameTimer(runtime, Duration.ofMillis(1));
        timers = timer.group(player);
    }

    @AfterEach void stop() {
        timer.close();
        runtime.close();
        assertEquals(List.of(), errors);
    }

    /** Runs {@code task} on the player's route and waits for it. */
    private void onRoute(GameTask<GameContext> task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        runtime.execute(player, context -> {
            try {
                task.run(context);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test void aTimerRunsOnTheGroupsRouteWithItsContext() throws Exception {
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        onRoute(context -> timers.after(new BuffExpiry(7), Duration.ofMillis(10), later -> seen.add(Context.current())));
        assertEquals(player, seen.poll(5, TimeUnit.SECONDS));
    }

    @Test void aKeySetAgainReplacesItsTimer() throws Exception {
        BlockingQueue<String> fired = new LinkedBlockingQueue<>();
        onRoute(context -> {
            timers.after(new BuffExpiry(7), Duration.ofMillis(20), later -> fired.add("first"));
            timers.every("tick", Duration.ofMillis(5), later -> fired.add("first tick"));
            timers.after(new BuffExpiry(7), Duration.ofMillis(40), later -> fired.add("renewed"));
            timers.after("tick", Duration.ofMillis(70), later -> fired.add("last tick"));
            timers.after(new BuffExpiry(8), Duration.ofMillis(100), later -> fired.add("another buff"));
        });
        assertEquals("renewed", fired.poll(5, TimeUnit.SECONDS));
        assertEquals("last tick", fired.poll(5, TimeUnit.SECONDS));
        assertEquals("another buff", fired.poll(5, TimeUnit.SECONDS), "other keys are left alone");
    }

    @Test void cancelStopsOneKeyAndCancelAllEveryTimer() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        List<Boolean> cancelled = new CopyOnWriteArrayList<>();
        List<Scheduled> all = new CopyOnWriteArrayList<>();
        onRoute(context -> {
            all.add(timers.after(new BuffExpiry(7), Duration.ofMillis(20), later -> fired.incrementAndGet()));
            all.add(timers.every("tick", Duration.ofMillis(5), later -> fired.incrementAndGet()));
            all.add(timers.cron("reset", "* * * * * *", later -> fired.incrementAndGet()));
            cancelled.add(timers.cancel(new BuffExpiry(7)));
            cancelled.add(timers.cancel(new BuffExpiry(7)));
            cancelled.add(timers.cancel("never set"));
            timers.cancelAll();   // the player logs out
            cancelled.add(timers.cancel("tick"));
        });
        assertEquals(List.of(true, false, false, false), cancelled);
        for (Scheduled scheduled : all) assertTrue(scheduled.isDone());
        TimeUnit.MILLISECONDS.sleep(1200);
        assertEquals(0, fired.get());
    }

    @Test void aTimerDueOnceLeavesTheGroupAsItRunsSoItsTaskCanSetItsKeyAgain() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        List<Boolean> cancelled = new CopyOnWriteArrayList<>();
        GameTask<GameContext> again = new GameTask<>() {
            @Override public void run(GameContext context) {
                cancelled.add(timers.cancel("regen"));   // it has left the group: nothing to cancel
                if (fired.incrementAndGet() < 3) timers.after("regen", Duration.ofMillis(5), this);
                three.countDown();
            }
        };
        onRoute(context -> timers.after("regen", Duration.ofMillis(5), again));
        assertTrue(three.await(5, TimeUnit.SECONDS));
        TimeUnit.MILLISECONDS.sleep(50);
        assertEquals(3, fired.get());
        assertEquals(List.of(false, false, false), cancelled);
    }

    @Test void aGroupIsUsedFromTasksOfItsRouteOnly() throws Exception {
        assertThrows(IllegalStateException.class, () -> timers.after("k", Duration.ofMillis(5), later -> {}), "outside a task");
        assertThrows(IllegalStateException.class, () -> timers.cancel("k"));
        assertThrows(IllegalStateException.class, timers::cancelAll);
        BlockingQueue<Throwable> thrown = new LinkedBlockingQueue<>();
        runtime.execute(GameContext.of(PLAYER, 2002), context -> {
            try {
                timers.every("k", Duration.ofMillis(5), later -> {});
                thrown.add(new AssertionError("another player's task used the group"));
            } catch (IllegalStateException expected) {
                thrown.add(expected);
            }
        });
        assertEquals(IllegalStateException.class, thrown.poll(5, TimeUnit.SECONDS).getClass());
        assertThrows(IllegalArgumentException.class, () -> timer.group(GameContext.of(99, 1)), "an unregistered domain");
    }

    @Test void aGroupsTimerIsCountedAsTheTaskTheApplicationWrote() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        onRoute(context -> timers.after("k", Duration.ofMillis(5), new Expire(fired)));
        assertTrue(fired.await(5, TimeUnit.SECONDS));
        runtime.close();
        assertEquals(1, calls.get(Expire.class.getName()).sum());
        assertFalse(calls.containsKey(TimerGroup.class.getName()), "the group's own wrapping shows nowhere");
    }

    private record Expire(CountDownLatch fired) implements GameTask<GameContext> {
        @Override public void run(GameContext context) { fired.countDown(); }
    }
}
