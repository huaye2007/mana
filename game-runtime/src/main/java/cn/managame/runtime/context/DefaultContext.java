package cn.managame.runtime.context;

public class DefaultContext implements Context {
    private final int routeDomain; private final long routeKey;
    public DefaultContext(int routeDomain, long routeKey) { this.routeDomain = routeDomain; this.routeKey = routeKey; }
    public int routeDomain() { return routeDomain; }
    public long routeKey() { return routeKey; }
}
