package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.RouteDiagnostics;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveDiagnosticsTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 1);
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void done(RouteTask task) throws Exception { task.get(3, TimeUnit.SECONDS); }
    private static GameRuntime.Builder builder() {
        return GameRuntime.builder().exceptionHandler(error -> {})
                .executionDomain(PLAYER.type(), ExecutionDomain.platform("diagnostics").threads(1).build());
    }

    @Test void identifiesBlockedHandlerAndQueuedEntityWithoutStoppingRuntime() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = builder().build()) {
            RouteTask running = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> { entered.countDown(); await(release); });
            RouteTask same, other;
            try {
                await(entered);
                same = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> {});
                other = runtime.dispatch(PLAYER.type(), 2, () -> {});
                RouteDiagnostics snapshot = runtime.routeDiagnostics(PLAYER).orElseThrow();
                assertEquals("diagnostics", snapshot.domain());
                assertEquals("dispatch", snapshot.runningSource());
                assertNotNull(snapshot.threadName());
                assertTrue(snapshot.runningFor().compareTo(Duration.ZERO) > 0);
                assertEquals(1, snapshot.queuedTasks());
                assertEquals("dispatch", snapshot.oldestQueuedSource());
                assertTrue(snapshot.oldestQueuedFor().compareTo(Duration.ZERO) > 0);
                RouteDiagnostics waiting = runtime.routeDiagnostics(new Route(PLAYER.type(), 2)).orElseThrow();
                assertNull(waiting.runningSource()); assertNull(waiting.threadName());
                assertEquals(Duration.ZERO, waiting.runningFor());
                assertEquals(1, waiting.queuedTasks());
                assertEquals(PLAYER, runtime.routeDiagnostics(1).getFirst().route());
                assertEquals(2, runtime.routeDiagnostics(10).size());
                assertFalse(running.isDone());
            } finally { release.countDown(); }
            done(running); done(same); done(other);
            assertTrue(runtime.close(Duration.ofSeconds(3)).terminated());
            assertTrue(runtime.routeDiagnostics(PLAYER).isEmpty());
            assertTrue(runtime.routeDiagnostics(10).isEmpty());
        }
    }

    @Test void slowSamplesAreBoundedImmutableAndIncludeFailure() throws Exception {
        try (var runtime = builder().slowTaskDiagnostics(Duration.ZERO, 2).build()) {
            done(runtime.dispatch(PLAYER.type(), 1, () -> {}));
            done(runtime.dispatch(PLAYER.type(), 2, () -> {}));
            var failure = runtime.dispatch(PLAYER.type(), 3, () -> { throw new IllegalStateException("slow failure"); });
            assertThrows(ExecutionException.class, () -> done(failure));
            var samples = runtime.recentSlowTasks();
            assertEquals(2, samples.size());
            assertEquals(2, samples.getFirst().route().key());
            assertEquals(3, samples.getLast().route().key());
            assertTrue(samples.getLast().failed());
            assertFalse(samples.getFirst().failed());
            assertEquals("dispatch", samples.getLast().source());
            assertTrue(samples.getLast().executionTime().compareTo(Duration.ZERO) > 0);
            assertThrows(UnsupportedOperationException.class, samples::clear);
            done(runtime.dispatch(PLAYER.type(), 4, () -> {}));
            assertEquals(2, samples.getFirst().route().key());
            assertEquals(4, runtime.recentSlowTasks().getLast().route().key());
        }
    }

    @Test void recordingIsOptInAndArgumentsAreBounded() throws Exception {
        try (var runtime = builder().build()) {
            done(runtime.dispatch(PLAYER.type(), 1, () -> {}));
            assertTrue(runtime.recentSlowTasks().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> runtime.routeDiagnostics(0));
            assertThrows(IllegalArgumentException.class, () -> runtime.routeDiagnostics(10001));
        }
        assertThrows(IllegalArgumentException.class, () -> builder().slowTaskDiagnostics(Duration.ofNanos(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> builder().slowTaskDiagnostics(Duration.ZERO, -1));
    }
}
