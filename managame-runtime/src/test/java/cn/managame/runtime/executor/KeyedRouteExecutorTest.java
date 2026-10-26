package cn.managame.runtime.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class KeyedRouteExecutorTest {
    private static final int SLACK = Runtime.getRuntime().availableProcessors() * 2;

    private static void eventually(BooleanSupplier condition, String message) throws InterruptedException {
        for (int i = 0; i < 250 && !condition.getAsBoolean(); i++) TimeUnit.MILLISECONDS.sleep(20);
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void runAndWait(KeyedRouteExecutor executor, long key) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(key, done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test void aDrainedQueueIsCachedAndReusedByItsKey() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofSeconds(60), 100);
        try {
            runAndWait(executor, 1);
            eventually(() -> executor.idleQueueCount() == 1, "the drained queue stays cached");
            KeyedRouteExecutor.KeyQueue first = executor.queue(1);

            runAndWait(executor, 1);
            eventually(() -> executor.idleQueueCount() == 1, "the queue is idle again");
            assertSame(first, executor.queue(1), "the key's queue is reused, not created again");
        } finally {
            executor.close();
        }
    }

    @Test void idleQueuesExpire() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofMillis(100), 100);
        try {
            for (long key = 0; key < 10; key++) runAndWait(executor, key);
            eventually(() -> executor.queueCount() == 0 && executor.idleQueueCount() == 0,
                    "queues idle past the timeout are dropped");
            runAndWait(executor, 1); // an expired key simply gets a new queue
        } finally {
            executor.close();
        }
    }

    @Test void theIdleCacheIsBoundedSoMemoryFollowsBusyKeys() throws Exception {
        int cap = 1000, keys = 100_000, busy = 10;
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofSeconds(60), cap);
        try {
            CountDownLatch release = new CountDownLatch(1), done = new CountDownLatch(keys);
            for (long key = 0; key < busy; key++) executor.execute(key, () -> await(release));
            for (long key = busy; key < busy + keys; key++) executor.execute(key, done::countDown);
            assertTrue(done.await(30, TimeUnit.SECONDS));

            eventually(() -> executor.queueCount() <= busy + cap + SLACK,
                    "100 000 keys ran, yet only the busy keys and about " + cap + " cached queues remain: " + executor.queueCount());
            assertTrue(executor.idleQueueCount() <= cap + SLACK);
            release.countDown();
        } finally {
            executor.close();
        }
    }

    @Test void withoutACacheDrainedQueuesAreDroppedAtOnce() throws Exception {
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 16, Duration.ofSeconds(60), 0);
        try {
            CountDownLatch done = new CountDownLatch(1000);
            for (long key = 0; key < 1000; key++) executor.execute(key, done::countDown);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            eventually(() -> executor.queueCount() == 0, "nothing stays cached");
        } finally {
            executor.close();
        }
    }

    @Test void aKeyThatKeepsGoingIdleAndBusyKeepsItsOrder() throws Exception {
        // Tiny tasks submitted one by one make the queue go idle and restart over and over.
        KeyedRouteExecutor executor = new KeyedRouteExecutor("test", 1 << 20, Duration.ofSeconds(60), 100);
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
            executor.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
