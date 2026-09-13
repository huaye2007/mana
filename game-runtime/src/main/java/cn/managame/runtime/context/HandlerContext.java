package cn.managame.runtime.context;

import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteType;

import java.util.Objects;

public class HandlerContext {
    private final Route route;
    private final Metadata metadata;

    public HandlerContext(Route route, Metadata metadata) {
        this.route = Objects.requireNonNull(route);
        this.metadata = Objects.requireNonNull(metadata);
    }

    public Route route() { return route; }
    public Class<? extends RouteType> routeType() { return route.type(); }
    public long routeKey() { return route.key(); }
    public Metadata metadata() { return metadata; }
}
