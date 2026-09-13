package cn.managame.runtime.route;

/** Annotation sentinel; never invoked. */
public final class UnspecifiedRouteKeyResolver implements RouteKeyResolver<Object> {
    private UnspecifiedRouteKeyResolver() {}
    public long resolve(Object value) { throw new UnsupportedOperationException(); }
}
