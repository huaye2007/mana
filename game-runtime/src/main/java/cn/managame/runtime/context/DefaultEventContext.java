package cn.managame.runtime.context;

import cn.managame.runtime.event.Event;

import cn.managame.core.Metadata;
public class DefaultEventContext extends DefaultInvocationContext implements EventContext {
    private final Event event;
    public DefaultEventContext(Event event, int businessIdType, long businessId, Metadata metadata) {
        super(event.routeDomain(), event.routeKey(), businessIdType, businessId, metadata); this.event = event;
    }
    public Event event() { return event; }
}
