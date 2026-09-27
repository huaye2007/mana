package cn.managame.runtime;

import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.protocol.ProtocolProvider;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.route.RouteKeyBinding;

import cn.managame.runtime.internal.RuntimeCompiler;
import java.time.*;
import java.util.*;
public final class GameRuntimeBuilder {
    private List<RouteDomain> domains = List.of();
    private List<RouteExecutorBinding> executors = List.of();
    private List<ProtocolProvider> providers = List.of();
    private List<RouteKeyBinding<?>> keys = List.of();
    private List<Object> handlers = List.of(), events = List.of(), crons = List.of();
    private ZoneId cronZone = ZoneId.of("UTC");
    private RuntimeErrorHandler errors = e -> System.getLogger("cn.managame.runtime").log(
        System.Logger.Level.ERROR, "Runtime error " + e.errorCode() + " at " + e.routeDomain() + "/" + e.routeKey(), e.cause());
    private GameRuntimeBuilder() {}
    public static GameRuntimeBuilder builder() { return new GameRuntimeBuilder(); }
    private static <T> List<T> copy(Iterable<? extends T> source) {
        List<T> result = new ArrayList<>(); source.forEach(v -> result.add(Objects.requireNonNull(v))); return List.copyOf(result);
    }
    public GameRuntimeBuilder routeDomains(Iterable<RouteDomain> v) { domains = copy(v); return this; }
    public GameRuntimeBuilder routeExecutors(Iterable<RouteExecutorBinding> v) { executors = copy(v); return this; }
    public GameRuntimeBuilder protocols(Iterable<? extends ProtocolProvider> v) { providers = copy(v); return this; }
    public GameRuntimeBuilder routeKeys(Iterable<RouteKeyBinding<?>> v) { keys = copy(v); return this; }
    public GameRuntimeBuilder handlers(Iterable<?> v) { handlers = copy(v); return this; }
    public GameRuntimeBuilder eventHandlers(Iterable<?> v) { events = copy(v); return this; }
    public GameRuntimeBuilder cronHandlers(Iterable<?> v) { crons = copy(v); return this; }
    public GameRuntimeBuilder errorHandler(RuntimeErrorHandler v) { errors = Objects.requireNonNull(v); return this; }
    public GameRuntimeBuilder cronZone(ZoneId v) { cronZone = Objects.requireNonNull(v); return this; }
    public GameRuntime build() {
        return RuntimeCompiler.build(domains, executors, providers, keys, handlers, events, crons, cronZone, errors);
    }
}
