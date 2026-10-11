package cn.managame.runtime.executor;

public final class RouteExecutors {
    private RouteExecutors() {}
    public static RouteExecutor platformThreads(int count) { return new StripedRouteExecutor(count, 65536); }
    /** 65536 queued tasks in total, at most {@value VirtualThreadRouteExecutor#DEFAULT_ROUTE_CAPACITY} per Route. */
    public static RouteExecutor virtualThreads() { return new VirtualThreadRouteExecutor(65536); }
}
