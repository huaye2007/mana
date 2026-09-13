package cn.managame.runtime.execution;

import cn.managame.runtime.route.RouteType;

import java.util.*;

/** Initialization output consumed by the runtime, independent of how it was compiled. */
record RuntimeBindings(CommandRegistry commands, EventRegistry events, List<HandlerBindings.CronBinding> crons) {
    Set<Class<? extends RouteType>> routeTypes() {
        Set<Class<? extends RouteType>> types = new HashSet<>(commands.routeTypes());
        types.addAll(events.routeTypes());
        crons.forEach(cron -> types.add(cron.route().type()));
        return types;
    }
    RuntimeBindings { crons = List.copyOf(crons); }
}
