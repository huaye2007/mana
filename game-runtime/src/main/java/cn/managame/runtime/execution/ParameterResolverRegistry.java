package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;

import java.util.*;

/** Command semantic parameter bindings. Events receive their registered event directly. */
public final class ParameterResolverRegistry {
    record Binding(ParameterResolver<?> resolver, boolean routeSource) {}
    private final Map<Class<?>, Binding> bindings;

    private ParameterResolverRegistry(Map<Class<?>, Binding> bindings) { this.bindings = Map.copyOf(bindings); }

    Binding bind(Class<?> parameter) {
        Binding binding = bindings.get(parameter);
        if (binding == null) throw new IllegalArgumentException("Missing Command ParameterResolver: " + parameter);
        return binding;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<Class<?>, Binding> bindings = new HashMap<>();

        /** Resolve inside the command's active route, with HandlerContexts.current() available. */
        public <T> Builder register(Class<T> parameter, ParameterResolver<T> resolver) {
            return add(parameter, resolver, false);
        }

        /**
         * Opt in to pre-route command identity resolution. Must be thread-safe, nonblocking and
         * read only immutable payload/session identity, never protected game state.
         * Only the selected route source runs before admission; other slots run on-route.
         */
        public <T> Builder registerRouteSource(Class<T> parameter, ParameterResolver<T> resolver) {
            return add(parameter, resolver, true);
        }

        private <T> Builder add(Class<T> parameter, ParameterResolver<T> resolver, boolean routeSource) {
            Objects.requireNonNull(parameter);
            Objects.requireNonNull(resolver);
            if (parameter == void.class || HandlerContext.class.isAssignableFrom(parameter))
                throw new IllegalArgumentException("Unsupported parameter: " + parameter);
            if (bindings.putIfAbsent(parameter, new Binding(resolver, routeSource)) != null)
                throw new IllegalArgumentException("Duplicate Command ParameterResolver: " + parameter);
            return this;
        }

        public ParameterResolverRegistry build() { return new ParameterResolverRegistry(bindings); }
    }
}
