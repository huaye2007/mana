package cn.managame.runtime.execution;

import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.protocol.Protocol;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteKeyResolver;
import cn.managame.runtime.route.RouteType;

import java.util.*;
import java.util.function.Function;

/** Immutable execution plans; scanning and type adaptation belong to initialization. */
final class HandlerBindings {
    private HandlerBindings() {}

    record RouteResolver(Class<?> source, RouteKeyResolver<Object> resolver, Class<?> valueType) {
        RouteResolver(Class<?> source, RouteKeyResolver<Object> resolver) { this(source, resolver, boxed(source)); }
        long resolve(Object value) { return resolver.resolve(valueType.cast(value)); }
    }
    record ArgumentSlot(Function<CommandHandlerInvocation, Object> resolver, boolean routeSource) {}
    record PreparedCommand(Route route, Object routeValue) {}

    record CommandBinding(Protocol protocol, Class<? extends RouteType> routeType, RouteResolver routeResolver,
                          ArgumentSlot routeSource, HandlerInvokers.CommandInvoker invoker, String name) {
        PreparedCommand prepare(CommandHandlerInvocation invocation) {
            Object source = routeSource.resolver().apply(invocation);
            return new PreparedCommand(new Route(routeType, routeResolver.resolve(source)), source);
        }

        void invoke(CommandHandlerInvocation invocation, PreparedCommand prepared) {
            try { invoker.invoke(invocation, prepared.routeValue()); }
            catch (Throwable error) { throw propagate(error); }
        }
    }

    record EventBinding(Class<? extends RouteType> routeType, RouteResolver resolver, HandlerInvokers.EventInvoker invoker, int order, String name) {
        Route route(Object event) { return new Route(routeType, resolver.resolve(event)); }
        void invoke(Object event) {
            try { invoker.invoke(event); }
            catch (Throwable error) { throw propagate(error); }
        }
    }

    record CronBinding(CronExpression expression, Route route, HandlerInvokers.CronInvoker invoker, String name) {
        void invoke() {
            try { invoker.invoke(); }
            catch (Throwable error) { throw propagate(error); }
        }
    }

    static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "char" -> Character.class;
            default -> throw new IllegalArgumentException("Invalid semantic parameter: " + type);
        };
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException r) return r;
        if (error instanceof Error e) throw e;
        return new java.util.concurrent.CompletionException(error);
    }
}
