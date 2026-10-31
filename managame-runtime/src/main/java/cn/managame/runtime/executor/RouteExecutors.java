package cn.managame.runtime.executor;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** The built-in RouteExecutors. Use a new instance per domain. */
public final class RouteExecutors {
    public static final int DEFAULT_MAX_PENDING_PER_KEY = 4096;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final int DEFAULT_MAX_IDLE_QUEUES = 100_000;
    public static final int DEFAULT_MAX_PENDING_PER_THREAD = 65536;
    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private RouteExecutors() {}

    /**
     * One queue per route key, drained by a virtual thread: a blocking task holds up only its own key. Drained
     * queues are cached for reuse, see {@link #virtualThreads(int, Duration, int)}, with the defaults
     * {@link #DEFAULT_MAX_PENDING_PER_KEY}, {@link #DEFAULT_IDLE_TIMEOUT} and {@link #DEFAULT_MAX_IDLE_QUEUES}.
     */
    public static RouteExecutor virtualThreads() {
        return virtualThreads(DEFAULT_MAX_PENDING_PER_KEY, DEFAULT_IDLE_TIMEOUT, DEFAULT_MAX_IDLE_QUEUES);
    }

    /**
     * @param maxPendingPerKey tasks one key may have waiting; more are refused
     * @param idleTimeout how long a drained queue stays cached for its key before it is dropped
     * @param maxIdleQueues cached queues kept at most; zero drops every queue as soon as it is drained
     */
    public static RouteExecutor virtualThreads(int maxPendingPerKey, Duration idleTimeout, int maxIdleQueues) {
        if (idleTimeout.toMillis() <= 0) throw new IllegalArgumentException("idleTimeout must be at least one millisecond");
        if (maxIdleQueues < 0) throw new IllegalArgumentException("maxIdleQueues must not be negative");
        return new KeyedRouteExecutor("game-route-" + INSTANCES.incrementAndGet(),
                positive(maxPendingPerKey, "maxPendingPerKey"), idleTimeout, maxIdleQueues);
    }

    /**
     * {@code threads} platform threads, a key always on thread {@code hash(key) % threads}: no state per key, but
     * a blocking task holds up every key on its thread. A thread with
     * {@link #DEFAULT_MAX_PENDING_PER_THREAD} tasks waiting refuses more.
     */
    public static RouteExecutor striped(int threads) { return striped(threads, DEFAULT_MAX_PENDING_PER_THREAD); }

    public static RouteExecutor striped(int threads, int maxPendingPerThread) {
        return new StripedRouteExecutor("game-striped-" + INSTANCES.incrementAndGet(), positive(threads, "threads"),
                positive(maxPendingPerThread, "maxPendingPerThread"));
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
