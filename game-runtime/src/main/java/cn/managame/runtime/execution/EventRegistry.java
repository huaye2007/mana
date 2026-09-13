package cn.managame.runtime.execution;

import cn.managame.runtime.route.RouteType;

import java.util.*;
/** Exact event-type subscriptions, sorted once by order then stable registration order. */
public final class EventRegistry {
    private final Map<Class<?>, List<HandlerBindings.EventBinding>> bindings;
    EventRegistry(Map<Class<?>, List<HandlerBindings.EventBinding>> source) {
        Map<Class<?>, List<HandlerBindings.EventBinding>> copy = new HashMap<>();
        source.forEach((type, list) -> copy.put(type, list.stream()
                .sorted(Comparator.comparingInt(HandlerBindings.EventBinding::order)).toList()));
        bindings = Map.copyOf(copy);
    }
    List<HandlerBindings.EventBinding> bindings(Class<?> event) {
        var result = bindings.get(event);
        if (result == null) throw new IllegalArgumentException("Unregistered event type: " + event);
        return result;
    }
    Set<Class<? extends RouteType>> routeTypes() {
        Set<Class<? extends RouteType>> types = new HashSet<>();
        bindings.values().forEach(list -> list.forEach(binding -> types.add(binding.routeType())));
        return types;
    }
    public int subscriberCount(Class<?> event) { return bindings(event).size(); }
}
