package cn.managame.demo.event;

import cn.managame.runtime.event.Event;
import cn.managame.demo.common.runtime.GameDomain;

public record DemoEvent(long routeKey, String message) implements Event {
    public static final int ROUTE_DOMAIN = GameDomain.ROLE_ID;

    @Override
    public int routeDomain() {
        return ROUTE_DOMAIN;
    }
}
