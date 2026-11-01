package cn.managame.runtime.executor;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;

import cn.managame.runtime.GameRuntimeException;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One queue per route key, drained by a virtual thread while it has tasks, so a task that blocks holds up only its
 * own key. Adding a task to a key's queue takes no lock: a compare-and-set counts it in. A drained queue stays for its
 * key and is reused when the key gets work again; a sweeper retires queues idle for {@code idleTimeout}, so memory
 * follows the keys used lately. Virtual threads are not reused: they are cheap to create and are not meant to be
 * pooled.
 */
final class KeyedRouteExecutor implements RouteExecutor {
    /** A long-busy key yields this often, since virtual threads are not time-sliced. */
    private static final int YIELD_EVERY = 64;
    /** The count of a queue the sweeper retired, which takes no more tasks. */
    private static final int RETIRED = -1;
    private final ConcurrentHashMap<Long, KeyQueue> queues = new ConcurrentHashMap<>();
    private final ThreadFactory threads;
    private final int maxPendingPerKey;
    private final long idleTimeoutNanos;
    private final Thread sweeper;
    private volatile boolean closed;

    KeyedRouteExecutor(String name, int maxPendingPerKey, Duration idleTimeout) {
        threads = Thread.ofVirtual().name(name + "-", 0).factory();
        this.maxPendingPerKey = maxPendingPerKey;
        idleTimeoutNanos = idleTimeout.toNanos();
        sweeper = Thread.ofVirtual().name(name + "-sweeper").start(this::sweepUntilClosed);
    }

    /**
     * Counts the task into its key's queue, a queue the sweeper retired meanwhile being replaced by a new one, then
     * queues it; whoever finds the queue empty starts its drain.
     */
    @Override public void execute(long routeKey, Runnable task) {
        KeyQueue queue = queues.get(routeKey);
        int pending = RETIRED;
        while (pending == RETIRED) {
            if (queue == null) queue = queues.computeIfAbsent(routeKey, key -> new KeyQueue());
            pending = queue.countIn(routeKey, maxPendingPerKey);
            if (pending == RETIRED) {
                queues.remove(routeKey, queue);
                queue = null;
            }
        }
        queue.tasks.offer(task);
        if (pending == 0) {
            KeyQueue started = queue;
            threads.newThread(() -> drain(started)).start();
        }
    }

    /** Runs the queue's tasks until its count falls to zero; a task counted in after that starts a drain of its own. */
    private void drain(KeyQueue queue) {
        int ran = 0;
        do {
            Runnable task;
            while ((task = queue.tasks.poll()) == null) Thread.onSpinWait();   // counted in, queued a moment later
            task.run();
            if (++ran % YIELD_EVERY == 0) Thread.yield();
            if (queue.pending.get() == 1) queue.idleSince = System.nanoTime();   // going idle unless a task arrives
        } while (queue.pending.decrementAndGet() > 0);
    }

    /** Retires every queue idle for the timeout, once per timeout; a compare-and-set leaves alone any that just got work. */
    private void sweepUntilClosed() {
        long intervalMillis = Math.max(1, idleTimeoutNanos / 1_000_000);
        while (!closed) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            queues.forEach((key, queue) -> {
                if (queue.isIdle() && now - queue.idleSince >= idleTimeoutNanos && queue.pending.compareAndSet(0, RETIRED))
                    queues.remove(key, queue);
            });
        }
    }

    @Override public void close() {
        closed = true;
        sweeper.interrupt();
    }

    /** Queues in the map, busy or idle. */
    int queueCount() { return queues.size(); }

    KeyQueue queue(long key) { return queues.get(key); }

    static final class KeyQueue {
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        /** Tasks counted in and not yet run to the end, the running one included; RETIRED once retired. */
        final AtomicInteger pending = new AtomicInteger();
        /** When the queue last went idle; written by its drain, read by the sweeper after the count. */
        volatile long idleSince = System.nanoTime();

        /**
         * Counts one more task in and returns the count before it, or RETIRED, counting nothing, for a retired queue.
         * Throws ROUTE_EXECUTOR_OVERLOADED when {@code maxWaiting} tasks wait besides the running one.
         */
        int countIn(long routeKey, int maxWaiting) {
            int count;
            do {
                count = pending.get();
                if (count == RETIRED) return RETIRED;
                if (count > maxWaiting)
                    throw new GameRuntimeException(ROUTE_EXECUTOR_OVERLOADED, "route key " + routeKey + " already has " + maxWaiting + " tasks waiting");
            } while (!pending.compareAndSet(count, count + 1));
            return count;
        }

        /** Idle: in the map with nothing counted in. */
        boolean isIdle() { return pending.get() == 0; }
    }
}
