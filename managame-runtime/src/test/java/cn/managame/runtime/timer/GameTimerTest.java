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
import cn.managame.runtime.RequestContext;
import cn.managame.runtime.executor.RouteExecutors;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
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
            timers[id] = timer.after(GameContext.of(PLAYER, id), Duration.ofMillis(50 + id % 50), context -> {
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
}
