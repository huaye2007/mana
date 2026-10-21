package cn.managame.runtime.executor;

public interface RouteExecutor extends AutoCloseable {
    /** Nonblocking admission. Accepted tasks execute serially and in submission order per (domain,key). */
    RouteExecuteStatus tryExecute(int routeDomain, long routeKey, Runnable task);

    /**
     * Admits the return leg of work the Route itself started — a cross-Route call's completion or a captured
     * continuation — without the capacity limits of {@link #tryExecute}; only a closed executor refuses it
     * (RT-CALL-06). That work has already happened elsewhere, so refusing its return would discard its result
     * rather than shed load, and the number of such tasks never exceeds the calls and captures the Route has
     * started and not yet had back. Order and mutual exclusion are those of {@link #tryExecute}; an accepted
     * continuation still counts toward the capacity that later ordinary submissions see.
     *
     * <p>The default delegates to {@link #tryExecute}, so an executor that does not override it keeps refusing
     * continuations when full, and Runtime reports each refusal as ROUTE_CALLBACK_DISPATCH_FAILED.
     */
    default RouteExecuteStatus tryExecuteContinuation(int routeDomain, long routeKey, Runnable task) {
        return tryExecute(routeDomain, routeKey, task);
    }

    /**
     * This executor's own limits, for diagnostics only. The default reports nothing, so an existing
     * executor keeps working and simply has no denominator to show.
     */
    default RouteExecutorStats stats() { return RouteExecutorStats.unreported(); }

    default void close() {}
}
