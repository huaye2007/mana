package cn.managame.runtime.executor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes hashed onto single-thread stripes. A stripe holds at most {@code queueCapacity} waiting tasks from
 * ordinary submissions, not counting the one running; a continuation is queued past that limit and counts
 * toward it, so the stripe queues are unbounded and the limit is this executor's own count.
 */
public final class StripedRouteExecutor implements RouteExecutor {
    private final ThreadPoolExecutor[] stripes;
    /** Waiting tasks per stripe, from acceptance until the task starts. */
    private final AtomicInteger[] waiting;
    private final int queueCapacity;
    private volatile boolean closed;
    public StripedRouteExecutor(int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) throw new IllegalArgumentException();
        this.queueCapacity = queueCapacity;
        stripes = new ThreadPoolExecutor[workers];
        waiting = new AtomicInteger[workers];
        for (int i = 0; i < workers; i++) {
            stripes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), Thread.ofPlatform().name("managame-route-" + i + "-", 0).factory());
            waiting[i] = new AtomicInteger();
        }
    }
    public RouteExecuteStatus tryExecute(int domain, long key, Runnable task) { return submit(domain, key, task, true); }
    @Override public RouteExecuteStatus tryExecuteContinuation(int domain, long key, Runnable task) {
        return submit(domain, key, task, false);
    }
    private RouteExecuteStatus submit(int domain, long key, Runnable task, boolean bounded) {
        Objects.requireNonNull(task);
        if (closed) return RouteExecuteStatus.CLOSED;
        int index = Math.floorMod(31 * domain + Long.hashCode(key), stripes.length);
        AtomicInteger queued = waiting[index];
        for (;;) {
            int current = queued.get();
            if (bounded && current >= queueCapacity) return RouteExecuteStatus.OVERLOADED;
            if (queued.compareAndSet(current, current + 1)) break;
        }
        try {
            stripes[index].execute(() -> {
                queued.decrementAndGet();
                try { task.run(); } catch (Throwable e) { log(e); }
            });
            return RouteExecuteStatus.ACCEPTED;
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
            return RouteExecuteStatus.CLOSED;   // the queue is unbounded, so only a shut-down stripe refuses
        }
    }
    /** Routes share stripes, so a per-Route limit and an active-Route count do not exist here. */
    @Override public RouteExecutorStats stats() {
        return new RouteExecutorStats((long) stripes.length * queueCapacity, 0, 0);
    }
    public synchronized void close() { if (!closed) { closed = true; for (var stripe : stripes) stripe.shutdown(); } }
    static void log(Throwable e) { System.getLogger("cn.managame.runtime").log(System.Logger.Level.ERROR, "Route task failed", e); }
}
