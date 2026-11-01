package cn.managame.runtime.executor;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_CLOSED;
import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.runtime.GameRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class KeyedRouteExecutorTest {
    private static void eventually(BooleanSupplier condition, String message) throws InterruptedException {
        for (int i = 0; i < 250 && !condition.getAsBoolean(); i++) TimeUnit.MILLISECONDS.sleep(20);
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void runAndWait(KeyedRouteExecutor executor, long key) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(key, done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test void aDrainedQueueStaysAndIsReusedByItsKey() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofSeconds(60));
        try {
            runAndWait(executor, 1);
            KeyedRouteExecutor.KeyQueue first = executor.queue(1);
            eventually(first::isIdle, "the drained queue stays, idle");

            runAndWait(executor, 1);
            eventually(first::isIdle, "the queue is idle again");
            assertSame(first, executor.queue(1), "the key's queue is reused, not created again");
        } finally {
            executor.shutdown();
        }
    }

    @Test void queuesIdlePastTheTimeoutAreDroppedAndKeysGetNewOnes() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofMillis(100));
        try {
            for (long key = 0; key < 1000; key++) runAndWait(executor, key);
            eventually(() -> executor.queueCount() == 0, "queues idle past the timeout are dropped");
            runAndWait(executor, 1); // a dropped key simply gets a new queue
        } finally {
            executor.shutdown();
        }
    }

    @Test void aBusyQueueIsNeverDropped() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofMillis(20));
        try {
            CountDownLatch release = new CountDownLatch(1), ran = new CountDownLatch(2);
            executor.execute(1, () -> await(release));
            executor.execute(1, ran::countDown);
            TimeUnit.MILLISECONDS.sleep(200);   // many sweeps
            KeyedRouteExecutor.KeyQueue busy = executor.queue(1);
            executor.execute(1, ran::countDown);
            assertSame(busy, executor.queue(1), "the busy queue stayed, and took the task");
            release.countDown();
            assertTrue(ran.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    @Test void aKeyRefusesTasksBeyondItsWaitingLimit() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 2, Duration.ofSeconds(60));
        try {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            executor.execute(1, () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.execute(1, () -> {});
            executor.execute(1, () -> {});
            GameRuntimeException full = assertThrows(GameRuntimeException.class, () -> executor.execute(1, () -> {}));
            assertEquals(ROUTE_EXECUTOR_OVERLOADED, full.errorCode());
            runAndWait(executor, 2); // other keys are not held up
            release.countDown();
        } finally {
            executor.shutdown();
        }
    }

    @Test void aKeyThatKeepsGoingIdleAndBusyKeepsItsOrder() throws Exception {
        // Tiny tasks submitted one by one make the queue go idle and restart over and over, while a short timeout
        // has the sweeper retire it now and then.
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 1 << 20, Duration.ofMillis(1));
        try {
            int tasks = 50_000;
            List<Integer> ran = new CopyOnWriteArrayList<>();
            AtomicInteger running = new AtomicInteger(), overlaps = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(tasks);
            for (int i = 0; i < tasks; i++) {
                int value = i;
                executor.execute(1, () -> {
                    if (running.incrementAndGet() != 1) overlaps.incrementAndGet();
                    ran.add(value);
                    running.decrementAndGet();
                    done.countDown();
                });
                if (i % 100 == 0) Thread.onSpinWait();
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(0, overlaps.get());
            List<Integer> expected = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) expected.add(i);
            assertEquals(expected, ran);
        } finally {
            executor.shutdown();
        }
    }

    @Test void manyThreadsOnOneKeyNeverRunTwoTasksAtOnceNorLoseOne() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 1 << 20, Duration.ofMillis(1));
        int threads = 8, perThread = 20_000;
        AtomicInteger running = new AtomicInteger(), overlaps = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads * perThread);
        try (ExecutorService submitters = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++)
                submitters.execute(() -> {
                    for (int i = 0; i < perThread; i++)
                        executor.execute(7, () -> {
                            if (running.incrementAndGet() != 1) overlaps.incrementAndGet();
                            running.decrementAndGet();
                            done.countDown();
                        });
                });
            assertTrue(done.await(30, TimeUnit.SECONDS), "every task ran");
            assertEquals(0, overlaps.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test void shutdownRefusesNewKeysAndTerminatesOnceTheAcceptedTasksRan() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofSeconds(60));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        executor.execute(1, () -> {
            await(release);
            ran.incrementAndGet();
        });
        executor.execute(1, ran::incrementAndGet);
        runAndWait(executor, 2);   // a drained queue, retired at once on shutdown
        executor.shutdown();
        GameRuntimeException refused = assertThrows(GameRuntimeException.class, () -> executor.execute(3, () -> {}));
        assertEquals(ROUTE_EXECUTOR_CLOSED, refused.errorCode());
        assertFalse(executor.awaitTermination(50, TimeUnit.MILLISECONDS), "key 1 still runs");
        release.countDown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(2, ran.get(), "every accepted task ran");
        assertEquals(0, executor.queueCount());
        assertThrows(GameRuntimeException.class, () -> executor.execute(1, () -> {}), "a drained key is refused as well");
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
