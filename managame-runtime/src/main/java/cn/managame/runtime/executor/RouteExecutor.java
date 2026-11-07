package cn.managame.runtime.executor;

import java.util.concurrent.TimeUnit;

/**
 * Runs the tasks of one domain: tasks with the same route key one at a time in submission order, different
 * keys independently. Thread-safe. Built-in executors come from {@link RouteExecutors}.
 *
 * <p>An executor knows its own work, so it is the one that finishes it: {@link #shutdown} refuses new tasks while the
 * accepted ones run, and {@link #awaitTermination} waits for them, from the state the executor keeps anyway (its queues),
 * with nothing counting tasks in flight.
 */
public interface RouteExecutor {
    /**
     * Accepts a task that never throws. Throws GameRuntimeException, leaving the task unrun, with
     * ROUTE_EXECUTOR_OVERLOADED when it cannot take more now, or ROUTE_EXECUTOR_CLOSED once shut down.
     */
    void execute(long routeKey, Runnable task);

    /**
     * Accepts a task that a running task hands on, past every limit on load, so that what was accepted finishes what it
     * began. Throws GameRuntimeException with ROUTE_EXECUTOR_CLOSED once shut down. By default as {@link #execute}, which
     * suits an executor with no such limits.
     */
    default void handOn(long routeKey, Runnable task) { execute(routeKey, task); }

    /**
     * Refuses new tasks; those accepted still run, and the executor's threads end after them. Idempotent. A task given
     * while it shuts down is either refused or run before termination.
     */
    void shutdown();

    /** Waits for every accepted task to finish after shutdown; false when the timeout elapsed first. */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Tasks accepted and not finished, read when metrics are collected and while the runtime shuts down, which keeps an
     * executor open until no domain has any, so that tasks can still hand work on. 0 from an executor that does not tell,
     * which is shut down at once.
     */
    default long pending() { return 0; }

    /**
     * The most tasks accepted and not finished in one place, a route key or a thread that serves many, read when metrics
     * are collected: a stuck route shows here while new tasks keep joining it. 0 from an executor that does not tell.
     */
    default long maxPending() { return 0; }

    /** Route keys it keeps state for, read when metrics are collected; 0 from an executor that keeps none. */
    default long routes() { return 0; }
}
