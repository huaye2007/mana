package cn.managame.runtime.disruptor;

import cn.managame.runtime.diagnostics.ShutdownReport;
import cn.managame.runtime.execution.ExecutionDomain;
import cn.managame.runtime.execution.GameRuntime;
import cn.managame.runtime.execution.RouteTask;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteType;

import cn.managame.runtime.execution.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DisruptorRouteDispatcherTest {
    interface Room extends RouteType {}
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    static void done(RouteTask task) throws Exception { task.get(5, TimeUnit.SECONDS); }
    static GameRuntime runtime(DisruptorRouteDispatcher dispatcher) {
        return GameRuntime.builder().automaticScheduling(false).exceptionHandler(ignored -> {})
                .executionDomain(Room.class, ExecutionDomain.custom("rooms", () -> dispatcher).tasksPerTurn(1).build()).build();
    }

    @Test void fixedRoomThreadsPreserveFifoAcrossConcurrentProducersAndBatches() throws Exception {
        var dispatcher = new DisruptorRouteDispatcher("rooms", 4, 256);
        GameRuntime runtime = runtime(dispatcher);
        List<RouteTask> tasks = new ArrayList<>();
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        try (ExecutorService senders = Executors.newFixedThreadPool(8)) {
            List<Future<List<RouteTask>>> submissions = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                long key = i;
                submissions.add(senders.submit(() -> {
                    List<RouteTask> submitted = new ArrayList<>();
                    AtomicInteger sequence = new AtomicInteger();
                    AtomicReference<Thread> owner = new AtomicReference<>();
                    for (int j = 0; j < 100; j++) {
                        int expected = j;
                        submitted.add(runtime.dispatch(Room.class, key, () -> {
                            assertEquals(expected, sequence.getAndIncrement());
                            owner.compareAndSet(null, Thread.currentThread());
                            assertSame(owner.get(), Thread.currentThread());
                            assertFalse(Thread.currentThread().isVirtual());
                            workers.add(Thread.currentThread());
                        }));
                    }
                    return submitted;
                }));
            }
            for (var submission : submissions) tasks.addAll(submission.get());
            assertTrue(runtime.close(Duration.ofSeconds(5)).terminated());
            for (RouteTask task : tasks) done(task);
            assertEquals(3200, runtime.metrics().completedTasks());
            assertEquals(4, workers.size());
            assertEquals(0, dispatcher.runningBatches()); assertEquals(0, dispatcher.readyRoutes());
        } finally { runtime.close(); }
    }

    @Test void fullRingRejectsWithoutBlockingTheSubmissionThread() throws Exception {
        var dispatcher = new DisruptorRouteDispatcher("small", 1, 2);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (GameRuntime runtime = runtime(dispatcher)) {
            RouteTask first = runtime.dispatch(Room.class, 1, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                RouteTask second = runtime.dispatch(Room.class, 2, () -> {});
                RouteTask third = runtime.dispatch(Room.class, 3, () -> {});
                assertThrows(RejectedExecutionException.class, () -> runtime.dispatch(Room.class, 4, () -> fail("Rejected")));
                assertEquals(3, runtime.metrics().outstandingTasks());
                release.countDown(); done(first); done(second); done(third);
            } finally { release.countDown(); }
        }
    }

    @Test void saturatedRingPreservesAcceptedContinuationsAcrossManyWraps() throws Exception {
        var dispatcher = new DisruptorRouteDispatcher("handoff", 1, 2);
        try (GameRuntime runtime = runtime(dispatcher)) {
            for (int round = 0; round < 40; round++) {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                AtomicInteger sequence = new AtomicInteger();
                List<RouteTask> tasks = new ArrayList<>();
                tasks.add(runtime.dispatch(Room.class, 1, () -> {
                    assertEquals(0, sequence.getAndIncrement());
                    entered.countDown(); await(release);
                }));
                try {
                    await(entered);
                    for (int i = 1; i < 32; i++) {
                        int expected = i;
                        tasks.add(runtime.dispatch(Room.class, 1, () ->
                                assertEquals(expected, sequence.getAndIncrement())));
                    }
                    tasks.add(runtime.dispatch(Room.class, 2, () -> {}));
                    tasks.add(runtime.dispatch(Room.class, 3, () -> {}));
                    release.countDown();
                    for (RouteTask task : tasks) done(task);
                    assertEquals(32, sequence.get());
                } finally { release.countDown(); }
            }
            assertTrue(runtime.close(Duration.ofSeconds(2)).terminated());
            assertEquals(0, runtime.activeRoutes());
            assertEquals(1360, runtime.metrics().completedTasks());
            assertEquals(0, dispatcher.readyRoutes());
        }
    }

    @Test void hotEntityReleasesIngressSlotsAndYieldsToNewEntities() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var draining = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var dispatcher = new DisruptorRouteDispatcher("fair", 1, 2);
        try (GameRuntime runtime = runtime(dispatcher)) {
            AtomicInteger hotCompleted = new AtomicInteger();
            List<RouteTask> tasks = new ArrayList<>();
            tasks.add(runtime.dispatch(Room.class, 1, () -> {
                hotCompleted.incrementAndGet(); entered.countDown(); await(release);
            }));
            try {
                await(entered);
                for (int i = 1; i < 100; i++) {
                    int turn = i;
                    tasks.add(runtime.dispatch(Room.class, 1, () -> {
                        if (turn == 3) { draining.countDown(); await(resume); }
                        hotCompleted.incrementAndGet();
                    }));
                }
                tasks.add(runtime.dispatch(Room.class, 2, () -> assertTrue(hotCompleted.get() < 4)));
                tasks.add(runtime.dispatch(Room.class, 3, () -> assertTrue(hotCompleted.get() < 4)));
                release.countDown();
                // All original ring slots have been consumed; the hot entity is still
                // taking turns from the local ready queue and must release ingress slots.
                await(draining);
                tasks.add(runtime.dispatch(Room.class, 4, () -> assertTrue(hotCompleted.get() < 100)));
                tasks.add(runtime.dispatch(Room.class, 5, () -> assertTrue(hotCompleted.get() < 100)));
                resume.countDown();
                for (RouteTask task : tasks) done(task);
                assertEquals(100, hotCompleted.get());
            } finally { release.countDown(); resume.countDown(); }
        }
    }

    @Test void continuationMustComeFromTheOwningConsumer() throws Exception {
        var dispatcher = new DisruptorRouteDispatcher("owner", 1, 2);
        try {
            assertThrows(IllegalStateException.class, () ->
                    dispatcher.reschedule(new Route(Room.class, 1), () -> fail("Invalid continuation")));
            assertEquals(0, dispatcher.readyRoutes());
        } finally {
            dispatcher.shutdown();
            assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(2)));
        }
    }

    @Test void timedCloseLeavesBusinessRunningThenTerminatesAfterRelease() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var dispatcher = new DisruptorRouteDispatcher("closing", 1, 8);
        GameRuntime runtime = runtime(dispatcher);
        try {
            RouteTask work = runtime.dispatch(Room.class, 9, () -> { entered.countDown(); await(release); });
            await(entered);
            ShutdownReport report = runtime.close(Duration.ofMillis(20));
            assertFalse(report.terminated());
            assertEquals(new Route(Room.class, 9), report.pendingRoutes().getFirst().route());
            assertTrue(report.pendingRoutes().getFirst().threadName().contains("disruptor"));
            release.countDown(); done(work);
            assertTrue(runtime.close(Duration.ofSeconds(2)).terminated());
            assertTrue(dispatcher.awaitTermination(Duration.ZERO));
        } finally { release.countDown(); runtime.close(); }
    }
}
