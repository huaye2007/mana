package cn.managame.runtime.executor;

/**
 * Runs the tasks of one domain: tasks with the same routing key one at a time in submission order, different
 * keys independently. Thread-safe. Built-in executors come from {@link RouteExecutors}.
 */
public interface RouteExecutor {
    /**
     * Accepts a task that never throws. Throws GameRuntimeException with ROUTE_EXECUTOR_OVERLOADED, leaving the
     * task unrun, when it cannot take more now.
     */
    void execute(long routingKey, Runnable task);

    /** Called by the runtime after every accepted task has finished, to release threads. */
    default void close() {}
}
