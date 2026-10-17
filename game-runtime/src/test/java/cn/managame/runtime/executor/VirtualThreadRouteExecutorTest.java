package cn.managame.runtime.executor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class VirtualThreadRouteExecutorTest {
    private static void awaitRetired(VirtualThreadRouteExecutor executor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.activeRoutes() != 0) {
            if (System.nanoTime() >= deadline) fail("Route mailboxes were not retired");
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Task release timed out");
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new AssertionError(cause);
        }
    }

    @Test void idleRouteRetiresItsMailboxAndRunsAgainOnNextSubmission() throws Exception {
        try (var executor = new VirtualThreadRouteExecutor(2)) {
            var first = new CountDownLatch(1);
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, first::countDown));
            assertTrue(first.await(5, TimeUnit.SECONDS));
            awaitRetired(executor);
            var second = new CountDownLatch(1);
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, second::countDown));
            assertTrue(second.await(5, TimeUnit.SECONDS));
            awaitRetired(executor);
        }
    }

    @Test void queuedWorkRunsAfterRunningWorkInOrder() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var order = new CopyOnWriteArrayList<Integer>();
        try (var executor = new VirtualThreadRouteExecutor(2)) {
            try {
                executor.tryExecute(1, 42, () -> {
                    entered.countDown();
                    awaitRelease(release);
                    order.add(1);
                    done.countDown();
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, executor.activeRoutes());
                assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, () -> {
                    order.add(2);
                    done.countDown();
                }));
                release.countDown();
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2), order);
                awaitRetired(executor);
            } finally { release.countDown(); }
        }
    }

    @Test void runningRoutesSurviveCloseAndRetireAfterAcceptedWorkDrains() throws Exception {
        int routes = 128;
        var entered = new CountDownLatch(routes);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(routes);
        try (var executor = new VirtualThreadRouteExecutor(routes)) {
            try {
                for (int i = 0; i < routes; i++) {
                    assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(i % 2 + 1, i / 2 + 1, () -> {
                        entered.countDown();
                        try { awaitRelease(release); } finally { done.countDown(); }
                    }));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.close();
                assertEquals(routes, executor.activeRoutes());
                assertEquals(RouteExecuteStatus.CLOSED, executor.tryExecute(1, 999, () -> fail("Closed task ran")));
                release.countDown();
                assertTrue(done.await(5, TimeUnit.SECONDS));
                awaitRetired(executor);
            } finally { release.countDown(); }
        }
    }

    @Test void taskFailureDoesNotStrandTheRoute() throws Exception {
        try (var executor = new VirtualThreadRouteExecutor(2)) {
            var failed = new CountDownLatch(1);
            executor.tryExecute(1, 42, () -> {
                failed.countDown();
                throw new IllegalStateException("expected task failure");
            });
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            awaitRetired(executor);
            var ran = new AtomicInteger();
            var done = new CountDownLatch(1);
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, () -> {
                ran.incrementAndGet();
                done.countDown();
            }));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, ran.get());
            awaitRetired(executor);
        }
    }

    @Test void concurrentRetirementAndResubmissionRemainSerialOrderedAndExecuteOnce() throws Exception {
        var expected = new ArrayList<Integer>();
        var observed = new ConcurrentLinkedQueue<Integer>();
        var running = new AtomicInteger();
        var overlap = new AtomicBoolean();
        var submission = new ReentrantLock();
        try (var executor = new VirtualThreadRouteExecutor(64); var producers = Executors.newFixedThreadPool(4)) {
            for (int wave = 0; wave < 200; wave++) {
                var start = new CountDownLatch(1);
                var done = new CountDownLatch(32);
                List<Future<?>> submitted = new ArrayList<>();
                for (int producer = 0; producer < 4; producer++) {
                    submitted.add(producers.submit(() -> {
                        awaitRelease(start);
                        for (int i = 0; i < 8; i++) {
                            submission.lock();
                            try {
                                int sequence = expected.size();
                                expected.add(sequence);
                                assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 42, () -> {
                                    if (running.incrementAndGet() != 1) overlap.set(true);
                                    try { observed.add(sequence); }
                                    finally { running.decrementAndGet(); done.countDown(); }
                                }));
                            } finally { submission.unlock(); }
                            // Let the Route drain and retire between some submissions.
                            if (i % 3 == 0) Thread.yield();
                        }
                    }));
                }
                start.countDown();
                for (var future : submitted) future.get(5, TimeUnit.SECONDS);
                assertTrue(done.await(5, TimeUnit.SECONDS));
            }
            executor.close();
            awaitRetired(executor);
            assertFalse(overlap.get());
            assertEquals(0, running.get());
            assertEquals(6400, observed.size());
            assertEquals(expected, new ArrayList<>(observed));
        }
    }

    @Test void concurrentCapacityReservationsNeverOverAdmit() throws Exception {
        var start = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(8);
        var ran = new AtomicInteger();
        try (var executor = new VirtualThreadRouteExecutor(8); var producers = Executors.newFixedThreadPool(32)) {
            try {
                List<Future<RouteExecuteStatus>> results = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    int key = i + 1;
                    results.add(producers.submit(() -> {
                        awaitRelease(start);
                        return executor.tryExecute(1, key, () -> {
                            entered.countDown();
                            awaitRelease(release);
                            ran.incrementAndGet();
                        });
                    }));
                }
                start.countDown();
                int accepted = 0, rejected = 0;
                for (var result : results) {
                    var status = result.get(5, TimeUnit.SECONDS);
                    if (status == RouteExecuteStatus.ACCEPTED) accepted++;
                    else {
                        assertEquals(RouteExecuteStatus.OVERLOADED, status);
                        rejected++;
                    }
                }
                assertEquals(8, accepted);
                assertEquals(24, rejected);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.close();
                release.countDown();
                awaitRetired(executor);
                assertEquals(8, ran.get());
            } finally {
                start.countDown();
                release.countDown();
            }
        }
    }

    @Test void invalidCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualThreadRouteExecutor(0));
        assertThrows(IllegalArgumentException.class, () -> new VirtualThreadRouteExecutor(4, 0));
        assertThrows(IllegalArgumentException.class, () -> new VirtualThreadRouteExecutor(4, 5));
    }

    @Test void routeCapacityRejectsOnlyTheHotRoute() throws Exception {
        try (var executor = new VirtualThreadRouteExecutor(10, 2)) {
            var running = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 7, () -> {
                running.countDown();
                awaitRelease(release);
            }));
            assertTrue(running.await(5, TimeUnit.SECONDS));       // the running task does not occupy the queue
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 7, () -> {}));
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 7, () -> {}));
            assertEquals(RouteExecuteStatus.OVERLOADED, executor.tryExecute(1, 7, () -> {}));
            var other = new CountDownLatch(1);
            assertEquals(RouteExecuteStatus.ACCEPTED, executor.tryExecute(1, 8, other::countDown));
            assertTrue(other.await(5, TimeUnit.SECONDS));
            release.countDown();
        }
    }
}
