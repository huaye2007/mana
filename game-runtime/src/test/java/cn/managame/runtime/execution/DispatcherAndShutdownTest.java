package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.RuntimeShutdownException;
import cn.managame.runtime.diagnostics.ShutdownReport;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(15)
class DispatcherAndShutdownTest {
    static class Backend implements RouteDispatcher {
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final List<Route> submitted = new CopyOnWriteArrayList<>();
        public void dispatch(Route route, Runnable batch) { submitted.add(route); pool.execute(batch); }
        public void reschedule(Route route, Runnable batch) { submitted.add(route); pool.execute(batch); }
        public void shutdown() { pool.shutdown(); }
        public boolean awaitTermination(Duration timeout) throws InterruptedException {
            return pool.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    @Test void customBackendReceivesRouteAndRuntimePreservesSerialOrder() throws Exception {
        Backend backend = new Backend();
        var domain = ExecutionDomain.custom("custom", () -> backend).tasksPerTurn(1).build();
        var count = new AtomicInteger();
        List<RouteTask> tasks = new ArrayList<>();
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, domain).build()) {
            for (int i = 0; i < 100; i++) {
                int expected = i;
                tasks.add(runtime.dispatch(TestRoutes.Player.class, 97, () -> assertEquals(expected, count.getAndIncrement())));
            }
            for (RouteTask task : tasks) done(task);
            assertEquals(ExecutionDomain.Mode.CUSTOM, runtime.executionDomains().get("custom").mode());
            assertTrue(backend.submitted.stream().allMatch(route -> route.equals(new Route(TestRoutes.Player.class, 97))));
        }
        assertTrue(backend.pool.isTerminated());
    }

    @Test void brokenBackendContinuationCompletesAcceptedTasksExceptionallyAndReleasesRoute() throws Exception {
        Backend backend = new Backend() {
            public void reschedule(Route route, Runnable batch) {
                throw new IllegalStateException("backend failed");
            }
        };
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class,
                ExecutionDomain.custom("reject", () -> backend).tasksPerTurn(1).build()).build()) {
            RouteTask first = runtime.dispatch(TestRoutes.Player.class, 1, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                RouteTask second = runtime.dispatch(TestRoutes.Player.class, 1, () -> fail("Rejected batch must not run"));
                RouteTask third = runtime.dispatch(TestRoutes.Player.class, 1, () -> fail("Rejected batch must not run"));
                release.countDown(); done(first);
                assertThrows(ExecutionException.class, () -> done(second));
                assertThrows(ExecutionException.class, () -> done(third));
                assertTrue(runtime.close(Duration.ofSeconds(2)).terminated());
                assertEquals(0, runtime.metrics().outstandingTasks());
                assertEquals(2, runtime.metrics().failedTasks());
            } finally { release.countDown(); }
        }
    }

    @Test void inlineBackendCannotRunBusinessOnSubmittingThread() {
        RouteDispatcher inline = new RouteDispatcher() {
            public void dispatch(Route route, Runnable batch) { batch.run(); }
            public void reschedule(Route route, Runnable batch) { batch.run(); }
            public void shutdown() {}
            public boolean awaitTermination(Duration timeout) { return true; }
        };
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class,
                ExecutionDomain.custom("inline", () -> inline).build()).build()) {
            assertThrows(IllegalStateException.class,
                    () -> runtime.dispatch(TestRoutes.Player.class, 1, () -> fail("Inline business execution")));
            assertEquals(0, runtime.activeRoutes());
        }
    }

    @Test void guardsRejectTheThreePreviouslyReproducedWaitDeadlocks() throws Exception {
        for (int strategy = 0; strategy < 3; strategy++) {
            var config = strategy == 2 ? ExecutionDomain.virtual("wait").maxConcurrentRoutes(1)
                    : ExecutionDomain.platform("wait").threads(1).scheduling(strategy == 0
                    ? ExecutionDomain.Scheduling.KEY_AFFINITY : ExecutionDomain.Scheduling.BALANCED);
            var pending = new AtomicReference<RouteTask>();
            try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, config.build()).build()) {
                done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {
                    RouteTask target = runtime.dispatch(TestRoutes.Player.class, 2, () -> {});
                    pending.set(target);
                    assertThrows(IllegalStateException.class, target::join);
                    assertThrows(IllegalStateException.class, target::get);
                    assertThrows(IllegalStateException.class, () -> target.get(1, TimeUnit.SECONDS));
                }));
                done(pending.get());
                // Completed tasks remain readable from a platform handler.
                done(runtime.dispatch(TestRoutes.Player.class, 1, pending.get()::join));
            }
        }
    }

    @Test void platformWaitGuardAlsoAppliesToAnotherRuntime() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var spec = ExecutionDomain.platform("platform").threads(1).build();
        try (GameRuntime a = builder().executionDomain(TestRoutes.Player.class, spec).build();
             GameRuntime b = builder().executionDomain(TestRoutes.Player.class, spec).build()) {
            RouteTask blocked = b.dispatch(TestRoutes.Player.class, 1, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                done(a.dispatch(TestRoutes.Player.class, 1, () -> assertThrows(IllegalStateException.class, blocked::join)));
            } finally { release.countDown(); }
            done(blocked);
        }
    }

    @Test void deadlineReportsRunningAndQueuedTasksWithoutInterruptingThem() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(2);
        var interrupted = new AtomicBoolean();
        GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, ExecutionDomain.platform("players").threads(1).build())
                .executionDomain(TestRoutes.Guild.class, ExecutionDomain.virtual("guilds").maxConcurrentRoutes(1).build()).build();
        List<RouteTask> tasks = new ArrayList<>();
        try {
            for (Class<? extends RouteType> type : List.of(TestRoutes.Player.class, TestRoutes.Guild.class)) {
                tasks.add(runtime.dispatch(type, 1, () -> {
                    entered.countDown(); await(release); interrupted.set(Thread.currentThread().isInterrupted());
                }));
                tasks.add(runtime.dispatch(type, 1, () -> {}));
            }
            await(entered);
            long start = System.nanoTime();
            ShutdownReport report = runtime.close(Duration.ofMillis(30));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertFalse(report.terminated()); assertEquals(2, report.pendingRoutes().size());
            assertEquals(2, report.remainingDomains().size());
            for (var pending : report.pendingRoutes()) {
                assertEquals("dispatch", pending.source()); assertNotNull(pending.threadName());
                assertFalse(pending.runningFor().isZero()); assertEquals(1, pending.queuedTasks());
            }
            assertThrows(RejectedExecutionException.class, () -> runtime.dispatch(TestRoutes.Player.class, 2, () -> {}));
            assertFalse(tasks.getFirst().isDone());
            release.countDown();
            for (RouteTask task : tasks) done(task);
            assertTrue(runtime.close(Duration.ofSeconds(2)).terminated());
            assertFalse(interrupted.get());
        } finally { release.countDown(); runtime.close(); }
    }

    @Test void autoCloseUsesConfiguredDeadlineAndReportsFailure() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        GameRuntime runtime = builder().shutdownTimeout(Duration.ofMillis(20)).build();
        try {
            RouteTask task = runtime.dispatch(TestRoutes.Player.class, 1, () -> { entered.countDown(); await(release); });
            await(entered);
            RuntimeShutdownException failure = assertThrows(RuntimeShutdownException.class, runtime::close);
            assertFalse(failure.report().terminated());
            release.countDown(); done(task);
            assertTrue(runtime.close(Duration.ofSeconds(2)).terminated());
        } finally { release.countDown(); runtime.close(); }
        assertThrows(IllegalArgumentException.class, () -> builder().shutdownTimeout(Duration.ofSeconds(-1)));
    }

    @Test void automaticTimerPollingExistsOnlyWhenBusinessTimersExist() {
        try (GameRuntime runtime = builder().build()) {
            GameScheduler scheduler = new GameScheduler(runtime, TimerOptions.defaults());
            try {
                scheduler.start(true);
                assertFalse(scheduler.automaticPollingActive());
                TimerTask timer = scheduler.schedule(runtime.clock().now().plus(Duration.ofDays(1)), () -> {});
                assertTrue(scheduler.automaticPollingActive());
                timer.cancel();
                assertFalse(scheduler.automaticPollingActive());
            } finally { scheduler.close(); }
        }
    }

    @Test void duplicateBackendDeliveryCannotExecuteBusinessTwice() throws Exception {
        Backend backend = new Backend() {
            public void dispatch(Route route, Runnable batch) { pool.execute(() -> { batch.run(); batch.run(); }); }
            public void reschedule(Route route, Runnable batch) { dispatch(route, batch); }
        };
        AtomicInteger calls = new AtomicInteger();
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class,
                ExecutionDomain.custom("duplicate", () -> backend).tasksPerTurn(1).build()).build()) {
            List<RouteTask> tasks = new ArrayList<>();
            for (int i = 0; i < 30; i++) tasks.add(runtime.dispatch(TestRoutes.Player.class, 1, calls::incrementAndGet));
            for (RouteTask task : tasks) done(task);
            assertTrue(runtime.close(Duration.ofSeconds(2)).terminated());
            assertEquals(30, calls.get());
        }
    }
    @Test void laterFactoryFailureShutsDownAlreadyCreatedBackends() {
        Backend backend = new Backend();
        assertThrows(IllegalStateException.class, () -> builder()
                .executionDomain(TestRoutes.Player.class, ExecutionDomain.custom("first", () -> backend).build())
                .executionDomain(TestRoutes.Guild.class, ExecutionDomain.custom("second", () -> {
                    throw new IllegalStateException("factory failed");
                }).build()).build());
        assertTrue(backend.pool.isTerminated());
    }
}
