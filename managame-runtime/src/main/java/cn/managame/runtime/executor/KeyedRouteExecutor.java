package cn.managame.runtime.executor;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;

import cn.managame.runtime.GameRuntimeException;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One queue per routing key, drained by a virtual thread while it has tasks, so a task that blocks holds up only
 * its own key. A drained queue stays cached for its key and is reused when the key gets work again, instead of
 * being created and dropped on every burst. The cache is bounded twice: a sweeper drops queues idle for longer
 * than {@code idleTimeout}, and once {@code maxIdleQueues} are cached, further drained queues are dropped at
 * once. Memory is thus at most the busy keys plus about {@code maxIdleQueues}; concurrent drains may overshoot
 * the cap by a few. Virtual threads are not reused: they are cheap to create and are not meant to be pooled.
 */
final class KeyedRouteExecutor implements RouteExecutor {
    /** A long-busy key yields this often, since virtual threads are not time-sliced. */
    private static final int YIELD_EVERY = 64;
    private final ConcurrentHashMap<Long, KeyQueue> queues = new ConcurrentHashMap<>();
    /** Cached queues: in the map and not draining. Changed only under the map's lock for the key. */
    private final AtomicInteger idleQueues = new AtomicInteger();
    private final ThreadFactory threads;
    private final int maxPendingPerKey, maxIdleQueues;
    private final long idleTimeoutNanos;
    private final Thread sweeper;
    private volatile boolean closed;

    KeyedRouteExecutor(String name, int maxPendingPerKey, Duration idleTimeout, int maxIdleQueues) {
        threads = Thread.ofVirtual().name(name + "-", 0).factory();
        this.maxPendingPerKey = maxPendingPerKey;
        this.maxIdleQueues = maxIdleQueues;
        idleTimeoutNanos = idleTimeout.toNanos();
        sweeper = Thread.ofVirtual().name(name + "-sweeper").start(this::sweepUntilClosed);
    }

    @Override public void execute(long routingKey, Runnable task) {
        // Every change to a queue's state happens under the map's lock for its key, so a queue going idle or
        // being dropped never misses a task added at the same moment.
        KeyQueue queue = queues.compute(routingKey, (key, cached) -> {
            KeyQueue target = cached != null ? cached : new KeyQueue(key);
            if (target.waiting.get() >= maxPendingPerKey)
                throw new GameRuntimeException(ROUTE_EXECUTOR_OVERLOADED,
                        "routing key " + key + " already has " + maxPendingPerKey + " tasks waiting");
            target.waiting.incrementAndGet();
            target.tasks.offer(task);
            if (!target.draining) {
                if (cached != null) idleQueues.decrementAndGet(); // reusing a cached queue
                target.draining = true;
                target.startPending.set(true);
            }
            return target;
        });
        if (queue.startPending.compareAndSet(true, false)) threads.newThread(() -> drain(queue)).start();
    }

    private void drain(KeyQueue queue) {
        int ran = 0;
        boolean[] keepDraining = new boolean[1];
        while (true) {
            Runnable task = queue.tasks.poll();
            if (task != null) {
                queue.waiting.decrementAndGet();
                task.run();
                if (++ran % YIELD_EVERY == 0) Thread.yield();
                continue;
            }
            // Either a task arrived meanwhile, or the queue goes idle: cached for its key, or dropped when the
            // cache is full. Decided under the key's lock and read from a local, as the queue may restart at once.
            keepDraining[0] = false;
            queues.computeIfPresent(queue.key, (key, current) -> {
                keepDraining[0] = !current.tasks.isEmpty();
                if (keepDraining[0]) return current;
                current.draining = false;
                if (idleQueues.get() >= maxIdleQueues) return null;
                current.idleSince = System.nanoTime();
                idleQueues.incrementAndGet();
                return current;
            });
            if (!keepDraining[0]) return;
        }
    }

    private void sweepUntilClosed() {
        long interval = Math.max(1, idleTimeoutNanos / 1_000_000);
        while (!closed) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            for (Long key : queues.keySet()) {
                queues.computeIfPresent(key, (k, queue) -> {
                    if (queue.draining || now - queue.idleSince < idleTimeoutNanos) return queue;
                    idleQueues.decrementAndGet();
                    return null;
                });
            }
        }
    }

    @Override public void close() {
        closed = true;
        sweeper.interrupt();
    }

    /** Queues in the map, busy or cached. */
    int queueCount() { return queues.size(); }

    int idleQueueCount() { return idleQueues.get(); }

    KeyQueue queue(long key) { return queues.get(key); }

    static final class KeyQueue {
        final long key;
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        final AtomicInteger waiting = new AtomicInteger();
        /** Whoever claims this starts the drain after a queue turns busy. */
        final AtomicBoolean startPending = new AtomicBoolean();
        /** Guarded by the map's lock for the key. */
        boolean draining;
        long idleSince;

        KeyQueue(long key) { this.key = key; }
    }
}
