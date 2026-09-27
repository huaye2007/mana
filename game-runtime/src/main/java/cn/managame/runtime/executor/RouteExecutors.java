package cn.managame.runtime.executor;

public final class RouteExecutors {
    private RouteExecutors() {}
    public static RouteExecutor platformThreads(int count) { return new StripedRouteExecutor(count, 65536); }
    public static RouteExecutor virtualThreads() { return new VirtualThreadRouteExecutor(65536); }
}
