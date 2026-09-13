package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.RuntimeShutdownException;
import cn.managame.runtime.diagnostics.ShutdownReport;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(15)
class BackendLifecycleTest {
    static class Backend implements RouteDispatcher {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger shutdownCalls = new AtomicInteger();
        public void dispatch(Route route, Runnable batch) { executor.execute(batch); }
        public void reschedule(Route route, Runnable batch) { executor.execute(batch); }
        public void shutdown() { shutdownCalls.incrementAndGet(); executor.shutdown(); }
        public boolean awaitTermination(Duration timeout) throws InterruptedException {
            return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }
    static ExecutionDomain domain(String name, Backend backend) {
        return ExecutionDomain.custom(name, () -> backend).tasksPerTurn(1).build();
    }
    static GameRuntime runtime(Backend first, Backend second) {
        return builder().executionDomain(TestRoutes.Player.class, domain("players", first))
                .executionDomain(TestRoutes.Guild.class, domain("guilds", second)).build();
    }

    @Test void shutdownFailureDoesNotPreventOtherDomainsFromClosing() throws Exception {
        Backend broken = new Backend() {
            public void shutdown() { super.shutdown(); throw new IllegalStateException("shutdown failed"); }
        };
        Backend healthy = new Backend();
        GameRuntime runtime = runtime(broken, healthy);
        try {
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            done(runtime.dispatch(TestRoutes.Guild.class, 1, () -> {}));
            ShutdownReport report = runtime.close(Duration.ofSeconds(2));
            assertFalse(report.terminated());
            assertEquals(List.of("players"), report.remainingDomains());
            assertEquals(1, report.backendFailures().size());
            assertEquals("shutdown", report.backendFailures().getFirst().operation());
            assertEquals("shutdown failed", report.backendFailures().getFirst().cause().getMessage());
            assertEquals(1, healthy.shutdownCalls.get());
            assertTrue(healthy.executor.isTerminated());
            RuntimeShutdownException exception = assertThrows(RuntimeShutdownException.class, runtime::close);
            assertEquals(1, exception.report().backendFailures().size());
        } finally { broken.executor.shutdown(); healthy.executor.shutdown(); }
    }

    @Test void shutdownFailureWhileLastRouteDrainsStillReleasesWaitersAndCompletions() throws Exception {
        Backend broken = new Backend() {
            public void shutdown() { super.shutdown(); throw new IllegalStateException("drain shutdown failed"); }
        };
        Backend healthy = new Backend();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        GameRuntime runtime = runtime(broken, healthy);
        try {
            RouteTask first = runtime.dispatch(TestRoutes.Player.class, 1, () -> { entered.countDown(); await(release); });
            await(entered);
            RouteTask second = runtime.dispatch(TestRoutes.Player.class, 1, () -> {});
            assertFalse(runtime.close(Duration.ZERO).terminated());
            release.countDown();
            done(first); done(second);
            ShutdownReport report = runtime.close(Duration.ofSeconds(2));
            assertFalse(report.terminated());
            assertTrue(report.pendingRoutes().isEmpty());
            assertEquals(0, runtime.activeRoutes());
            assertEquals(0, runtime.metrics().outstandingTasks());
            assertEquals("drain shutdown failed", report.backendFailures().getFirst().cause().getMessage());
            assertTrue(healthy.executor.isTerminated());
        } finally { release.countDown(); broken.executor.shutdown(); healthy.executor.shutdown(); }
    }

    @Test void awaitFailureDoesNotPreventWaitingForHealthyDomains() throws Exception {
        Backend broken = new Backend() {
            public boolean awaitTermination(Duration timeout) { throw new IllegalStateException("await failed"); }
        };
        Backend healthy = new Backend();
        GameRuntime runtime = runtime(broken, healthy);
        try {
            done(runtime.dispatch(TestRoutes.Guild.class, 1, () -> {}));
            ShutdownReport report = runtime.close(Duration.ofSeconds(2));
            assertFalse(report.terminated());
            assertEquals("awaitTermination", report.backendFailures().getFirst().operation());
            assertTrue(healthy.executor.isTerminated());
        } finally { broken.executor.shutdown(); healthy.executor.shutdown(); }
    }

    @Test void factoryFailureKeepsOriginalCauseAndSuppressesEarlierCleanupFailure() {
        Backend broken = new Backend() {
            public void shutdown() { super.shutdown(); throw new IllegalStateException("cleanup failed"); }
        };
        IllegalArgumentException creation = new IllegalArgumentException("factory failed");
        try {
            Throwable result = assertThrows(IllegalArgumentException.class, () -> builder()
                    .executionDomain(TestRoutes.Player.class, domain("players", broken))
                    .executionDomain(TestRoutes.Guild.class, ExecutionDomain.custom("guilds", () -> { throw creation; }).build())
                    .build());
            assertSame(creation, result);
            assertEquals(1, result.getSuppressed().length);
            assertEquals("cleanup failed", result.getSuppressed()[0].getMessage());
        } finally { broken.executor.shutdown(); }
    }

    @Test void ingressRejectionDoesNotCompeteWithAdmittedEntityContinuations() throws Exception {
        Backend backend = new Backend() {
            final AtomicInteger admissions = new AtomicInteger();
            public void dispatch(Route route, Runnable batch) {
                if (admissions.incrementAndGet() > 1) throw new RejectedExecutionException("ingress full");
                super.dispatch(route, batch);
            }
        };
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, domain("players", backend)).build()) {
            List<RouteTask> accepted = new ArrayList<>();
            accepted.add(runtime.dispatch(TestRoutes.Player.class, 1, () -> {
                entered.countDown(); await(release); assertEquals(0, sequence.getAndIncrement());
            }));
            try {
                await(entered);
                for (int i = 1; i < 100; i++) {
                    int expected = i;
                    accepted.add(runtime.dispatch(TestRoutes.Player.class, 1,
                            () -> assertEquals(expected, sequence.getAndIncrement())));
                }
                assertThrows(RejectedExecutionException.class, () -> runtime.dispatch(TestRoutes.Player.class, 2, () -> {}));
                release.countDown();
                for (RouteTask task : accepted) done(task);
                assertEquals(100, sequence.get());
                assertEquals(0, runtime.metrics().failedTasks());
            } finally { release.countDown(); }
        }
    }
}
