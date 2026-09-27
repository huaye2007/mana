package cn.managame.runtime.executor;

import java.util.*;
import java.util.concurrent.*;
public final class StripedRouteExecutor implements RouteExecutor {
    private final ThreadPoolExecutor[] stripes;
    private volatile boolean closed;
    public StripedRouteExecutor(int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) throw new IllegalArgumentException();
        stripes = new ThreadPoolExecutor[workers];
        for (int i = 0; i < workers; i++)
            stripes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().name("managame-route-" + i + "-", 0).factory());
    }
    public RouteExecuteStatus tryExecute(int domain, long key, Runnable task) {
        Objects.requireNonNull(task);
        if (closed) return RouteExecuteStatus.CLOSED;
        int hash = 31 * domain + Long.hashCode(key);
        ThreadPoolExecutor stripe = stripes[Math.floorMod(hash, stripes.length)];
        try { stripe.execute(() -> { try { task.run(); } catch (Throwable e) { log(e); } }); return RouteExecuteStatus.ACCEPTED; }
        catch (RejectedExecutionException e) { return closed || stripe.isShutdown() ? RouteExecuteStatus.CLOSED : RouteExecuteStatus.OVERLOADED; }
    }
    public synchronized void close() { if (!closed) { closed = true; for (var stripe : stripes) stripe.shutdown(); } }
    static void log(Throwable e) { System.getLogger("cn.managame.runtime").log(System.Logger.Level.ERROR, "Route task failed", e); }
}
