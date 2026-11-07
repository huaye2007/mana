package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.runtime.executor.RouteExecutors;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class RuntimeMetricsTest {
    private static final int PLAYER = 1, WORLD = 2;

    /** Stands in for a backend: adds up counters, and holds gauges until they are closed. */
    private static final class Backend implements Metrics {
        private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
        private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();

        private static String key(String name, String... tags) { return name + List.of(tags); }

        @Override public Counter counter(String name, String... tags) {
            return counts.computeIfAbsent(key(name, tags), key -> new LongAdder())::add;
        }

        @Override public Meter counter(String name, LongSupplier count, String... tags) { throw new UnsupportedOperationException(); }

        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            gauges.put(key(name, tags), value);
            return () -> gauges.remove(key(name, tags));
        }

        long count(String name, String... tags) { return counts.get(key(name, tags)).sum(); }

        double gauge(String name, String... tags) { return gauges.get(key(name, tags)).getAsDouble(); }
    }

    private final Backend backend = new Backend();

    @Test void tasksThatThrowAreCountedByDomainAndOthersReportNothing() {
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.striped(1)).domain(WORLD, RouteExecutors.striped(1))
                .errorHandler((context, cause) -> {}).metrics(backend).build();
        runtime.execute(GameContext.of(PLAYER, 1), context -> {});
        runtime.execute(GameContext.of(PLAYER, 1), context -> { throw new IllegalStateException("boom"); });
        runtime.execute(GameContext.of(WORLD, 1), context -> {});
        runtime.close();
        assertEquals(1, backend.count("managame.runtime.tasks.failed", "domain", "1"));
        assertEquals(0, backend.count("managame.runtime.tasks.failed", "domain", "2"));
    }

    @Test void refusalsAreCountedByCode() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads(1, Duration.ofSeconds(60))).metrics(backend).build();
        runtime.execute(GameContext.of(PLAYER, 1), context -> {
            running.countDown();
            release.await();
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        runtime.execute(GameContext.of(PLAYER, 1), context -> {});
        GameRuntimeException full = assertThrows(GameRuntimeException.class, () -> runtime.execute(GameContext.of(PLAYER, 1), context -> {}));
        assertEquals(ROUTE_EXECUTOR_OVERLOADED, full.errorCode());
        release.countDown();
        runtime.close();
        assertThrows(GameRuntimeException.class, () -> runtime.execute(GameContext.of(PLAYER, 1), context -> {}));
        assertEquals(1, backend.count("managame.runtime.tasks.refused", "domain", "1", "code", "ROUTE_EXECUTOR_OVERLOADED"));
        assertEquals(1, backend.count("managame.runtime.tasks.refused", "domain", "1", "code", "RUNTIME_CLOSED"));
    }

    @Test void gaugesReadWhatTheExecutorsHoldUntilClosed() throws Exception {
        CountDownLatch running = new CountDownLatch(2), release = new CountDownLatch(1);
        GameRuntime runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).domain(WORLD, RouteExecutors.striped(1))
                .metrics(backend).build();
        for (int domain : List.of(PLAYER, WORLD))
            runtime.execute(GameContext.of(domain, 1), context -> {
                running.countDown();
                release.await();
            });
        runtime.execute(GameContext.of(PLAYER, 1), context -> {});
        runtime.execute(GameContext.of(PLAYER, 2), context -> release.await());
        runtime.execute(GameContext.of(WORLD, 2), context -> {});
        assertTrue(running.await(5, TimeUnit.SECONDS));

        assertEquals(3, backend.gauge("managame.runtime.tasks.pending", "domain", "1"), "running or waiting, on two keys");
        assertEquals(2, backend.gauge("managame.runtime.routes.max_pending", "domain", "1"), "the fuller key");
        assertEquals(2, backend.gauge("managame.runtime.routes.active", "domain", "1"));
        assertEquals(2, backend.gauge("managame.runtime.tasks.pending", "domain", "2"));
        assertEquals(2, backend.gauge("managame.runtime.routes.max_pending", "domain", "2"), "its one thread");
        assertEquals(0, backend.gauge("managame.runtime.routes.active", "domain", "2"), "a striped domain keeps nothing per key");

        release.countDown();
        runtime.close();
        assertFalse(backend.gauges.containsKey(Backend.key("managame.runtime.tasks.pending", "domain", "1")), "closing lets go of the executors");
    }
}
