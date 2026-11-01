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
     * Refuses new tasks; those accepted still run, and the executor's threads end after them. Idempotent. A task given
     * while it shuts down is either refused or run before termination.
     */
    void shutdown();

    /** Waits for every accepted task to finish after shutdown; false when the timeout elapsed first. */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
}
