package cn.managame.runtime;

/**
 * Where and for whom a task runs: a context without its metadata. Event subscriptions, client handlers and
 * HTTP routes compute one from their input to decide the context the business logic runs with.
 */
public record Route(int domain, long routeKey, byte businessType, long businessId) {
    /** No business identity. */
    public static Route of(int domain, long routeKey) {
        return new Route(domain, routeKey, (byte) 0, 0);
    }

    public static Route of(int domain, long routeKey, byte businessType, long businessId) {
        return new Route(domain, routeKey, businessType, businessId);
    }
}
