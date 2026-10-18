package cn.managame.runtime.executor;

/**
 * What an executor can say about its own limits, so a queue depth can be judged instead of only read.
 *
 * @param capacity tasks admissible across all Routes before {@code tryExecute} returns OVERLOADED,
 *                 or 0 when this executor does not bound them or does not report the bound
 * @param routeCapacity tasks admissible for one Route before that Route alone is refused, or 0 when
 *                      this executor has no per-Route limit
 * @param activeRoutes Routes currently holding queued or running work, or 0 when not tracked; an
 *                     executor that shares threads between Routes does not know this
 */
public record RouteExecutorStats(long capacity, long routeCapacity, long activeRoutes) {
    private static final RouteExecutorStats UNREPORTED = new RouteExecutorStats(0, 0, 0);

    /** For an executor that publishes none of its limits; every reading reads 0. */
    public static RouteExecutorStats unreported() { return UNREPORTED; }
}
