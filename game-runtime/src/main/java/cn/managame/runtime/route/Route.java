package cn.managame.runtime.route;


import java.util.Objects;

/** An application-defined namespace and entity key, scoped to one GameRuntime instance. */
public record Route(Class<? extends RouteType> type, long key) {
    public Route { Objects.requireNonNull(type, "type"); }
}
