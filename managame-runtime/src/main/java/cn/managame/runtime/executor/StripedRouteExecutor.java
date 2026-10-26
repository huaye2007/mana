package cn.managame.runtime.executor;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;

import cn.managame.runtime.GameRuntimeException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A fixed set of platform threads; a routing key always runs on thread {@code hash(key) % threads}. No state
 * per key, so any number of keys costs nothing, but a task that blocks holds up every key on its thread.
 */
final class StripedRouteExecutor implements RouteExecutor {
    private final ThreadPoolExecutor[] stripes;

    StripedRouteExecutor(String name, int threads, int maxPendingPerThread) {
        stripes = new ThreadPoolExecutor[threads];
        for (int i = 0; i < threads; i++)
            stripes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(maxPendingPerThread), Thread.ofPlatform().name(name + "-" + i).factory());
    }

    @Override public void execute(long routingKey, Runnable task) {
        int stripe = Math.floorMod(Long.hashCode(routingKey), stripes.length);
        try {
            stripes[stripe].execute(task);
        } catch (RejectedExecutionException full) {
            throw new GameRuntimeException(ROUTE_EXECUTOR_OVERLOADED, "thread " + stripe + " of the striped executor is full");
        }
    }

    @Override public void close() {
        for (ThreadPoolExecutor stripe : stripes) stripe.shutdown();
    }
}
