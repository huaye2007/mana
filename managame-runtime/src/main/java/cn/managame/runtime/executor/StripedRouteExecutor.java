package cn.managame.runtime.executor;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_CLOSED;
import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;

import cn.managame.runtime.GameRuntimeException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A fixed set of platform threads; a route key always runs on thread {@code hash(key) % threads}. No state
 * per key, so any number of keys costs nothing, but a task that blocks holds up every key on its thread. Each thread is
 * a ThreadPoolExecutor of one, whose own shutdown and termination finish the work. Its queue has no capacity of its own:
 * {@link #execute} keeps it to the limit by reading its size, so threads racing may pass the limit by a few, and a task
 * handed on passes it on purpose.
 */
final class StripedRouteExecutor implements RouteExecutor {
    private final ThreadPoolExecutor[] stripes;
    private final int maxPendingPerThread;

    StripedRouteExecutor(String name, int threads, int maxPendingPerThread) {
        stripes = new ThreadPoolExecutor[threads];
        this.maxPendingPerThread = maxPendingPerThread;
        for (int i = 0; i < threads; i++)
            stripes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), Thread.ofPlatform().name(name + "-" + i).factory());
    }

    private int stripe(long routeKey) { return Math.floorMod(Long.hashCode(routeKey), stripes.length); }

    @Override public void execute(long routeKey, Runnable task) {
        int stripe = stripe(routeKey);
        if (stripes[stripe].getQueue().size() >= maxPendingPerThread)
            throw new GameRuntimeException(ROUTE_EXECUTOR_OVERLOADED, "thread " + stripe + " of the striped executor is full");
        handOn(routeKey, task);
    }

    @Override public void handOn(long routeKey, Runnable task) {
        try {
            stripes[stripe(routeKey)].execute(task);
        } catch (RejectedExecutionException refused) {
            throw new GameRuntimeException(ROUTE_EXECUTOR_CLOSED, "the striped executor is shut down");
        }
    }

    @Override public long pending() {
        long pending = 0;
        for (ThreadPoolExecutor stripe : stripes) pending += stripe.getQueue().size() + stripe.getActiveCount();
        return pending;
    }

    @Override public long maxPending() {
        long most = 0;
        for (ThreadPoolExecutor stripe : stripes) most = Math.max(most, stripe.getQueue().size() + stripe.getActiveCount());
        return most;
    }

    @Override public String toString() { return "striped(" + stripes.length + (stripes.length == 1 ? " thread)" : " threads)"); }

    @Override public void shutdown() {
        for (ThreadPoolExecutor stripe : stripes) stripe.shutdown();
    }

    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (ThreadPoolExecutor stripe : stripes)
            if (!stripe.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return false;
        return true;
    }
}
