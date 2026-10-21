package cn.managame.runtime.executor;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One lock-free mailbox per active Route, drained by a virtual thread that exists only while the Route has
 * work. A mailbox lives in the active map only while it holds or runs tasks; the consumer that finds it
 * empty retires it, and the next submission creates a fresh one, so idle Routes cost no memory.
 *
 * <p>Admission is bounded twice: {@code capacity} tasks across all Routes protects memory, and
 * {@code routeCapacity} queued tasks per Route stops one hot Route (for example a flooding client) from
 * consuming the shared capacity. Either limit returns OVERLOADED without blocking. A continuation passes
 * both limits (it is the return of work the Route already started) and still counts toward them.
 */
public final class VirtualThreadRouteExecutor implements RouteExecutor {
    /** Default per-Route queue limit used when none is given. */
    public static final int DEFAULT_ROUTE_CAPACITY = 1024;
    private static final ThreadFactory THREADS = Thread.ofVirtual().name("managame-route").factory();

    private record Route(int domain, long key) {}

    /**
     * Tasks plus a count of queued-and-running tasks. A submission that raises the count from zero starts the
     * consumer; the consumer decrements after each task and, on reaching zero, retires the mailbox by setting
     * the count to RETIRED. A submitter that sees RETIRED replaces the mailbox. Because the count reaches zero
     * only after the last task finished, at most one consumer runs a Route's tasks, in submission order.
     */
    private static final class Mailbox {
        static final int RETIRED = -1;
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger count = new AtomicInteger();
    }

    private final ConcurrentHashMap<Route, Mailbox> activeMailboxes = new ConcurrentHashMap<>();
    // High bit means closed; remaining bits count reserved, queued and running tasks.
    private final AtomicLong admission = new AtomicLong();
    private final int capacity, routeCapacity;

    public VirtualThreadRouteExecutor(int capacity) {
        this(capacity, Math.min(Math.max(capacity, 1), DEFAULT_ROUTE_CAPACITY));
    }

    public VirtualThreadRouteExecutor(int capacity, int routeCapacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        if (routeCapacity < 1 || routeCapacity > capacity)
            throw new IllegalArgumentException("routeCapacity must be 1..capacity");
        this.capacity = capacity;
        this.routeCapacity = routeCapacity;
    }

    public RouteExecuteStatus tryExecute(int domain, long key, Runnable task) { return submit(domain, key, task, true); }

    @Override public RouteExecuteStatus tryExecuteContinuation(int domain, long key, Runnable task) {
        return submit(domain, key, task, false);
    }

    private RouteExecuteStatus submit(int domain, long key, Runnable task, boolean bounded) {
        Objects.requireNonNull(task);
        RouteExecuteStatus status = reserve(bounded);
        if (status != RouteExecuteStatus.ACCEPTED) return status;
        boolean submitted = false;
        try {
            Route route = new Route(domain, key);
            for (;;) {
                Mailbox mailbox = activeMailboxes.computeIfAbsent(route, ignored -> new Mailbox());
                int count = mailbox.count.get();
                if (count == Mailbox.RETIRED) {
                    activeMailboxes.remove(route, mailbox); // the retiring consumer may not have removed it yet
                    continue;
                }
                // The count includes the running task, so a busy Route may queue routeCapacity tasks behind it.
                if (bounded && count > routeCapacity) return RouteExecuteStatus.OVERLOADED;
                if (!mailbox.count.compareAndSet(count, count + 1)) continue;
                mailbox.queue.offer(task);
                if (count == 0) start(route, mailbox, task);
                submitted = true;
                return RouteExecuteStatus.ACCEPTED;
            }
        } finally { if (!submitted) admission.decrementAndGet(); }
    }

    /** Starts the consumer this submission is obliged to start; on failure hands the obligation back. */
    private void start(Route route, Mailbox mailbox, Runnable task) {
        try {
            THREADS.newThread(() -> drain(route, mailbox)).start();
        } catch (RuntimeException | Error cause) {
            // Withdraw this task. Later submitters saw a nonzero count and started nothing, so if they left
            // tasks behind, this thread must still start their consumer.
            mailbox.queue.remove(task);
            if (mailbox.count.decrementAndGet() == 0) {
                if (mailbox.count.compareAndSet(0, Mailbox.RETIRED)) activeMailboxes.remove(route, mailbox);
            } else {
                THREADS.newThread(() -> drain(route, mailbox)).start();
            }
            throw cause;
        }
    }

    private RouteExecuteStatus reserve(boolean bounded) {
        for (;;) {
            long state = admission.get();
            if (state < 0) return RouteExecuteStatus.CLOSED;
            if (bounded && state >= capacity) return RouteExecuteStatus.OVERLOADED;
            if (admission.compareAndSet(state, state + 1)) return RouteExecuteStatus.ACCEPTED;
        }
    }

    private void drain(Route route, Mailbox mailbox) {
        for (int spins = 0;;) {
            Runnable task = mailbox.queue.poll();
            if (task == null) {
                // A submitter counted its task but has not offered it yet; it is moments away.
                if (++spins % 64 == 0) Thread.yield(); else Thread.onSpinWait();
                continue;
            }
            spins = 0;
            try { task.run(); }
            catch (Throwable cause) { StripedRouteExecutor.log(cause); }
            finally { admission.decrementAndGet(); }
            if (mailbox.count.decrementAndGet() == 0) {
                // A submitter that raises 0 → 1 first starts a new consumer; otherwise this one retires the mailbox.
                if (mailbox.count.compareAndSet(0, Mailbox.RETIRED)) activeMailboxes.remove(route, mailbox);
                return;
            }
        }
    }

    /** Routes that currently hold or run work. */
    int activeRoutes() { return activeMailboxes.size(); }

    /** Stops admission; accepted tasks still run to completion. */
    /** Both bounds are configured, and the mailbox map is exactly the Routes holding work. */
    @Override public RouteExecutorStats stats() {
        return new RouteExecutorStats(capacity, routeCapacity, activeMailboxes.size());
    }

    public void close() {
        admission.getAndUpdate(state -> state | Long.MIN_VALUE);
    }
}
