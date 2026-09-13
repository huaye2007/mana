package cn.managame.runtime.execution;

import cn.managame.runtime.route.RouteType;

import java.util.List;

/** Application-owned namespaces for the test fixtures; none are built into Runtime. */
final class TestRoutes {
    private TestRoutes() {}
    interface Account extends RouteType {}
    interface Player extends RouteType {}
    interface Guild extends RouteType {}
    interface Battle extends RouteType {}
    static final List<Class<? extends RouteType>> ALL = List.of(Account.class, Player.class, Guild.class, Battle.class);
}
