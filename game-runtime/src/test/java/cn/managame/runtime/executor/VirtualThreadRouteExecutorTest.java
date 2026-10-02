package cn.managame.runtime.executor;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class VirtualThreadRouteExecutorTest {
    private static Map<?, ?> active(VirtualThreadRouteExecutor executor) throws ReflectiveOperationException {
        var field = VirtualThreadRouteExecutor.class.getDeclaredField("activeMailboxes");
        field.setAccessible(true); return (Map<?, ?>) field.get(executor);
    }
    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> idle(VirtualThreadRouteExecutor executor) throws ReflectiveOperationException {
        var field = VirtualThreadRouteExecutor.class.getDeclaredField("idleMailboxes");
        field.setAccessible(true); return (Cache<Object, Object>) field.get(executor);
    }
    private static void awaitInactive(VirtualThreadRouteExecutor executor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!active(executor).isEmpty()) {
            if (System.nanoTime() >= deadline) fail("Accepted Route work did not finish");
            Thread.sleep(1);
        }
    }
    private static Object awaitCached(VirtualThreadRouteExecutor executor) throws Exception {
        awaitInactive(executor);
        var values = idle(executor).asMap().values().iterator();
        assertTrue(values.hasNext(), "Expected a retained idle mailbox"); return values.next();
    }
    private static void awaitEmpty(VirtualThreadRouteExecutor executor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!active(executor).isEmpty() || idle(executor).estimatedSize() != 0) {
            if (System.nanoTime() >= deadline) fail("Route mailboxes were not reclaimed");
            Thread.sleep(1);
        }
    }
    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Task release timed out");
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt(); throw new AssertionError(cause);
        }
    }

    @Test void idleMailboxIsReusedAndExpiryResetsAfterAnotherDrain() throws Exception {
        var time = new AtomicLong();
        try (var executor = new VirtualThreadRouteExecutor(2, Duration.ofMinutes(1), time::get)) {
            var first = new CountDownLatch(1);
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, first::countDown));
            assertTrue(first.await(5, TimeUnit.SECONDS)); Object original = awaitCached(executor);
            time.set(TimeUnit.SECONDS.toNanos(59)); idle(executor).cleanUp();
            assertSame(original, idle(executor).asMap().values().iterator().next());
            var second = new CountDownLatch(1);
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, second::countDown));
            assertTrue(second.await(5, TimeUnit.SECONDS)); assertSame(original, awaitCached(executor));
            time.set(TimeUnit.SECONDS.toNanos(60)); idle(executor).cleanUp();
            assertEquals(1, idle(executor).estimatedSize());
            time.set(TimeUnit.SECONDS.toNanos(119)); idle(executor).cleanUp();
            assertEquals(0, idle(executor).estimatedSize());
        }
    }

    @Test void automaticExpiryPreservesRunningWorkAndNeedsNoFurtherSubmissions() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(2);
        var order = new CopyOnWriteArrayList<Integer>();
        try (var executor = new VirtualThreadRouteExecutor(2, Duration.ofMillis(20))) {
            try {
                executor.tryExecute(1, 42, () -> {
                    entered.countDown(); awaitRelease(release); order.add(1); done.countDown();
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS)); Thread.sleep(80);
                assertEquals(1, active(executor).size()); assertEquals(0, idle(executor).estimatedSize());
                assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, () -> { order.add(2); done.countDown(); }));
                release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS)); awaitEmpty(executor);
                assertEquals(List.of(1, 2), order);
            } finally { release.countDown(); }
        }
    }

    @Test void runningRoutesSurviveCloseAndRetireAfterAcceptedWorkDrains() throws Exception {
        int routes = 128;
        var entered = new CountDownLatch(routes); var release = new CountDownLatch(1); var done = new CountDownLatch(routes);
        try (var executor = new VirtualThreadRouteExecutor(routes)) {
            try {
                for (int i = 0; i < routes; i++) {
                    assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(i % 2 + 1, i / 2 + 1, () -> {
                        entered.countDown(); try { awaitRelease(release); } finally { done.countDown(); }
                    }));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS)); executor.close();
                assertEquals(routes, active(executor).size()); assertEquals(0, idle(executor).estimatedSize());
                assertEquals(RouteExecuteStatus.CLOSED, executor.tryExecute(1, 999, () -> fail("Closed task ran")));
                release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS)); awaitEmpty(executor);
            } finally { release.countDown(); }
        }
    }

    @Test void taskFailureDoesNotStrandMailboxAndRouteCanBeRecreatedAfterExpiry() throws Exception {
        var time = new AtomicLong(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = new VirtualThreadRouteExecutor(2, Duration.ofMinutes(1), time::get)) {
            try {
                executor.tryExecute(1, 42, () -> { entered.countDown(); awaitRelease(release); throw new IllegalStateException("expected task failure"); });
                assertTrue(entered.await(5, TimeUnit.SECONDS)); Object failed = active(executor).values().iterator().next();
                release.countDown(); assertSame(failed, awaitCached(executor));
                time.set(TimeUnit.MINUTES.toNanos(1)); idle(executor).cleanUp(); assertEquals(0, idle(executor).estimatedSize());
                var ran = new AtomicInteger(); var done = new CountDownLatch(1);
                assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, () -> { ran.incrementAndGet(); done.countDown(); }));
                assertTrue(done.await(5, TimeUnit.SECONDS)); assertNotSame(failed, awaitCached(executor)); assertEquals(1, ran.get());
            } finally { release.countDown(); }
        }
    }

    @Test void concurrentExpiryAndResubmissionRemainSerialOrderedAndExecuteOnce() throws Exception {
        var expected = new ArrayList<Integer>(); var observed = new ConcurrentLinkedQueue<Integer>();
        var running = new AtomicInteger(); var overlap = new AtomicBoolean(); var time = new AtomicLong();
        var submission = new ReentrantLock(); var cleaning = new AtomicBoolean(true);
        try (var executor = new VirtualThreadRouteExecutor(64, Duration.ofSeconds(1), time::get);
             var producers = Executors.newFixedThreadPool(5)) {
            var cache = idle(executor);
            var cleanup = producers.submit(() -> {
                while (cleaning.get()) { time.addAndGet(TimeUnit.SECONDS.toNanos(1)); cache.cleanUp(); Thread.yield(); }
            });
            try {
                for (int wave = 0; wave < 80; wave++) {
                    var start = new CountDownLatch(1); var done = new CountDownLatch(32); List<Future<?>> submitted = new ArrayList<>();
                    for (int producer = 0; producer < 4; producer++) {
                        submitted.add(producers.submit(() -> {
                            awaitRelease(start);
                            for (int i = 0; i < 8; i++) {
                                submission.lock();
                                try {
                                    int sequence = expected.size(); expected.add(sequence);
                                    assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, () -> {
                                        if (running.incrementAndGet() != 1) overlap.set(true);
                                        try { Thread.yield(); observed.add(sequence); }
                                        finally { running.decrementAndGet(); done.countDown(); }
                                    }));
                                } finally { submission.unlock(); }
                            }
                        }));
                    }
                    start.countDown(); for (var future : submitted) future.get(5, TimeUnit.SECONDS);
                    assertTrue(done.await(5, TimeUnit.SECONDS));
                }
                executor.close(); awaitEmpty(executor); assertFalse(overlap.get()); assertEquals(0, running.get());
                assertEquals(2560, observed.size()); assertEquals(expected, new ArrayList<>(observed));
            } finally { cleaning.set(false); cleanup.get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void concurrentCapacityReservationsNeverOverAdmit() throws Exception {
        var start = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(8);
        var ran = new AtomicInteger();
        try (var executor = new VirtualThreadRouteExecutor(8); var producers = Executors.newFixedThreadPool(32)) {
            try {
                List<Future<RouteExecuteStatus>> results = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    int key = i + 1;
                    results.add(producers.submit(() -> {
                        awaitRelease(start);
                        return executor.tryExecute(1, key, () -> { entered.countDown(); awaitRelease(release); ran.incrementAndGet(); });
                    }));
                }
                start.countDown(); int accepted = 0, rejected = 0;
                for (var result : results) {
                    var status = result.get(5, TimeUnit.SECONDS);
                    if (status == RouteExecuteStatus.ACCEPTED) accepted++;
                    else { assertEquals(RouteExecuteStatus.OVERLOADED, status); rejected++; }
                }
                assertEquals(8, accepted); assertEquals(24, rejected); assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.close(); release.countDown(); awaitEmpty(executor); assertEquals(8, ran.get());
            } finally { start.countDown(); release.countDown(); }
        }
    }

    @Test void idleCacheIsSizeBoundedIndependentlyOfTaskCapacity() throws Exception {
        try (var executor = new VirtualThreadRouteExecutor(4)) {
            for (int key = 1; key <= 40; key++) {
                var done = new CountDownLatch(1);
                assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, key, done::countDown));
                assertTrue(done.await(5, TimeUnit.SECONDS)); awaitInactive(executor);
            }
            idle(executor).cleanUp(); assertTrue(idle(executor).estimatedSize() <= 4);
        }
    }

    @Test void invalidCapacityAndIdleTimeoutAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualThreadRouteExecutor(0));
        for (Duration value : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE)))
            assertThrows(IllegalArgumentException.class, () -> new VirtualThreadRouteExecutor(1, value));
        assertThrows(NullPointerException.class, () -> new VirtualThreadRouteExecutor(1, null));
    }
}