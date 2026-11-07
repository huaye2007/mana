package cn.managame.runtime.executor;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** The built-in RouteExecutors. Use a new instance per domain. */
public final class RouteExecutors {
    public static final int DEFAULT_MAX_PENDING_PER_KEY = 4096;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final int DEFAULT_MAX_KEYS = 1 << 20;
    public static final int DEFAULT_MAX_PENDING_PER_THREAD = 65536;
    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private RouteExecutors() {}

    /**
     * One queue per route key, drained by a virtual thread: a blocking task holds up only its own key. Tasks join a
     * queue without a lock, and a drained queue stays for its key until idle for long; see
     * {@link #virtualThreads(int, Duration, int)}, here with {@link #DEFAULT_MAX_PENDING_PER_KEY},
     * {@link #DEFAULT_IDLE_TIMEOUT} and {@link #DEFAULT_MAX_KEYS}.
     */
    public static RouteExecutor virtualThreads() {
        return virtualThreads(DEFAULT_MAX_PENDING_PER_KEY, DEFAULT_IDLE_TIMEOUT, DEFAULT_MAX_KEYS);
    }

    /** {@link #virtualThreads(int, Duration, int)} with {@link #DEFAULT_MAX_KEYS}. */
    public static RouteExecutor virtualThreads(int maxPendingPerKey, Duration idleTimeout) {
        return virtualThreads(maxPendingPerKey, idleTimeout, DEFAULT_MAX_KEYS);
    }

    /**
     * @param maxPendingPerKey tasks one key may have waiting besides the running one; more are refused
     * @param idleTimeout how long a drained queue stays for its key before it is dropped, so memory follows the keys
     *                    used within it
     * @param maxKeys queues it may hold, idle ones included; a key with no queue is refused above it, so keys that come
     *                from clients cannot grow memory without bound
     */
    public static RouteExecutor virtualThreads(int maxPendingPerKey, Duration idleTimeout, int maxKeys) {
        if (idleTimeout.toMillis() <= 0) throw new IllegalArgumentException("idleTimeout must be at least one millisecond");
        return new KeyedRouteExecutor("game-route-" + INSTANCES.incrementAndGet(), positive(maxPendingPerKey, "maxPendingPerKey"), idleTimeout,
                positive(maxKeys, "maxKeys"));
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
