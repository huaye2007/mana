package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.clock.GameClock;
import cn.managame.runtime.diagnostics.RuntimeShutdownException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class StartupCleanupTest {
    private static class Backend implements RouteDispatcher {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final List<String> order;
        final String name;
        Backend(String name, List<String> order) throws Exception {
            this.name = name; this.order = order;
            executor.submit(() -> {}).get(3, TimeUnit.SECONDS); // Eager resource, as with Disruptor.
        }
        public void dispatch(Route route, Runnable batch) { executor.execute(batch); }
        public void reschedule(Route route, Runnable batch) { executor.execute(batch); }
        public void shutdown() { order.add(name); executor.shutdown(); }
        public boolean awaitTermination(Duration timeout) throws InterruptedException {
            return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }
    private static ExecutionDomain domain(String name, Backend backend) {
        return ExecutionDomain.custom(name, () -> backend).build();
    }
    private static GameClock brokenClock(RuntimeException failure) {
        return new GameClock() {
            public Instant now() { return Instant.EPOCH; }
            public ZoneId zoneId() { return ZoneId.of("UTC"); }
            public long nanoTime() { throw failure; }
        };
    }

    @Test void schedulerConstructionFailureReleasesAllDomainsInReverseOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Backend first = new Backend("first", order), second = new Backend("second", order);
        var cause = new IllegalStateException("clock setup");
        try {
            assertSame(cause, assertThrows(IllegalStateException.class, () -> GameRuntime.builder()
                    .clock(brokenClock(cause))
                    .executionDomain(TestRoutes.Player.class, domain("first", first))
                    .executionDomain(TestRoutes.Guild.class, domain("second", second)).build()));
            assertEquals(List.of("second", "first"), order);
            assertTrue(first.executor.isTerminated());
            assertTrue(second.executor.isTerminated());
        } finally { first.executor.shutdownNow(); second.executor.shutdownNow(); }
    }

    @Test void factoryFailureUnwindsEarlierDomainsInReverseOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Backend first = new Backend("first", order), second = new Backend("second", order);
        var cause = new IllegalArgumentException("third factory");
        try {
            assertSame(cause, assertThrows(IllegalArgumentException.class, () -> GameRuntime.builder()
                    .executionDomain(TestRoutes.Player.class, domain("first", first))
                    .executionDomain(TestRoutes.Guild.class, domain("second", second))
                    .executionDomain(TestRoutes.Battle.class, ExecutionDomain.custom("third", () -> { throw cause; }).build()).build()));
            assertEquals(List.of("second", "first"), order);
            assertTrue(first.executor.isTerminated()); assertTrue(second.executor.isTerminated());
        } finally { first.executor.shutdownNow(); second.executor.shutdownNow(); }
    }

    static class CronHandler {
        @Cron(value = "0 * * * * ?", routeType = TestRoutes.Player.class, routeKey = 1)
        public void tick() {}
    }

    @Test void registrationFailureAfterSchedulerConstructionAlsoCleansUp() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Backend backend = new Backend("registered", order);
        var cause = new IllegalStateException("registration clock");
        AtomicInteger reads = new AtomicInteger();
        GameClock clock = new GameClock() {
            public Instant now() {
                if (reads.incrementAndGet() > 1) throw cause;
                return Instant.parse("2026-01-01T00:00:00Z");
            }
            public ZoneId zoneId() { return ZoneId.of("UTC"); }
        };
        try {
            assertSame(cause, assertThrows(IllegalStateException.class, () -> GameRuntime.builder()
                    .clock(clock).handler(new CronHandler())
                    .executionDomain(TestRoutes.Player.class, domain("registered", backend)).build()));
            assertEquals(List.of("registered"), order);
            assertTrue(backend.executor.isTerminated());
        } finally { backend.executor.shutdownNow(); }
    }

    @Test void clockFailureRemainsPrimaryWhenBackendCleanupAlsoFails() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Backend broken = new Backend("broken", order) {
            public void shutdown() { super.shutdown(); throw new IllegalStateException("cleanup"); }
        };
        var cause = new IllegalArgumentException("clock");
        try {
            Throwable failure = assertThrows(IllegalArgumentException.class, () -> GameRuntime.builder()
                    .clock(brokenClock(cause)).executionDomain(TestRoutes.Player.class, domain("broken", broken)).build());
            assertSame(cause, failure);
            assertEquals(1, failure.getSuppressed().length);
            var cleanup = assertInstanceOf(RuntimeShutdownException.class, failure.getSuppressed()[0]);
            assertEquals("cleanup", cleanup.report().backendFailures().getFirst().cause().getMessage());
            assertTrue(broken.executor.isShutdown());
        } finally { broken.executor.shutdownNow(); }
    }
}
