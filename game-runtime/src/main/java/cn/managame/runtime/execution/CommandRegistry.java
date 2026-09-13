package cn.managame.runtime.execution;

import cn.managame.runtime.route.RouteType;

import java.util.*;
/** Immutable, initialized request-to-handler bindings. */
public final class CommandRegistry {
    private final Map<Class<?>, HandlerBindings.CommandBinding> bindings;
    CommandRegistry(Map<Class<?>, HandlerBindings.CommandBinding> bindings) { this.bindings = Map.copyOf(bindings); }
    HandlerBindings.CommandBinding require(Class<?> request) {
        var binding = bindings.get(request);
        if (binding == null) throw new IllegalArgumentException("No command handler for " + request);
        return binding;
    }
    Set<Class<? extends RouteType>> routeTypes() {
        Set<Class<? extends RouteType>> types = new HashSet<>();
        bindings.values().forEach(binding -> types.add(binding.routeType()));
        return types;
    }
    public boolean contains(Class<?> requestType) { return bindings.containsKey(requestType); }
    public Set<Class<?>> requestTypes() { return bindings.keySet(); }
}
