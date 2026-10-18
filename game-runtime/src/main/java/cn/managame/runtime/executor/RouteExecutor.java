package cn.managame.runtime.executor;

public interface RouteExecutor extends AutoCloseable {
    /** Nonblocking admission. Accepted tasks execute serially and in submission order per (domain,key). */
    RouteExecuteStatus tryExecute(int routeDomain, long routeKey, Runnable task);

    /**
     * This executor's own limits, for diagnostics only. The default reports nothing, so an existing
     * executor keeps working and simply has no denominator to show.
     */
    default RouteExecutorStats stats() { return RouteExecutorStats.unreported(); }

    default void close() {}
}
