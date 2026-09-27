package cn.managame.runtime.executor;

public interface RouteExecutor extends AutoCloseable {
    /** Nonblocking admission. Accepted tasks execute serially and in submission order per (domain,key). */
    RouteExecuteStatus tryExecute(int routeDomain, long routeKey, Runnable task);
    default void close() {}
}
