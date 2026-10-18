package cn.managame.runtime;

import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.handler.ClientResultCodec;
import cn.managame.runtime.handler.HandlerArgumentBinding;
import cn.managame.runtime.handler.HandlerContextFactory;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.route.RouteKeyBinding;
import cn.managame.runtime.http.HttpContextFactory;
import cn.managame.runtime.http.HttpResultCodec;
import cn.managame.runtime.http.HttpRequestCodec;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.runtime.internal.RuntimeCompiler;
import cn.managame.runtime.internal.RuntimeSettings;
import java.time.*;
import java.util.*;
public final class GameRuntimeBuilder {
    private List<RouteDomain> domains = List.of();
    private List<RouteExecutorBinding> executors = List.of();
    private ProtocolRegistry protocols = ProtocolRegistry.empty();
    private List<RouteKeyBinding<?>> keys = List.of();
    private List<Object> handlers = List.of(), events = List.of(), crons = List.of();
    private List<HandlerArgumentBinding<?>> arguments = List.of();
    private HandlerContextFactory handlerContexts;
    private ClientResultCodec clientResults;
    private List<Object> httpHandlers = List.of();
    private HttpContextFactory httpContexts;
    private HttpResultCodec httpResults = HttpResultCodec.json();
    private HttpRequestCodec httpRequests = HttpRequestCodec.json();
    private ZoneId cronZone = ZoneId.of("UTC");
    private boolean executionTiming = true;
    private RuntimeErrorHandler errors = e -> System.getLogger("cn.managame.runtime").log(System.Logger.Level.ERROR,
        "Runtime error " + FrameworkErrorCodes.describe(e.errorCode()) + " at route " + e.routeDomain() + "/" + e.routeKey(),
        e.cause());
    private GameRuntimeBuilder() {}
    public static GameRuntimeBuilder builder() { return new GameRuntimeBuilder(); }
    private static <T> List<T> copy(Iterable<? extends T> source) {
        List<T> result = new ArrayList<>(); source.forEach(v -> result.add(Objects.requireNonNull(v))); return List.copyOf(result);
    }
    public GameRuntimeBuilder routeDomains(Iterable<RouteDomain> v) { domains = copy(v); return this; }
    public GameRuntimeBuilder routeExecutors(Iterable<RouteExecutorBinding> v) { executors = copy(v); return this; }
    public GameRuntimeBuilder protocols(ProtocolRegistry v) { protocols = Objects.requireNonNull(v); return this; }
    public GameRuntimeBuilder routeKeys(Iterable<RouteKeyBinding<?>> v) { keys = copy(v); return this; }
    public GameRuntimeBuilder handlers(Iterable<?> v) { handlers = copy(v); return this; }
    public GameRuntimeBuilder handlerArguments(Iterable<? extends HandlerArgumentBinding<?>> v) { arguments = copy(v); return this; }
    public GameRuntimeBuilder handlerContextFactory(HandlerContextFactory v) { handlerContexts = Objects.requireNonNull(v); return this; }
    /** Required when any Handler returns a response; Runtime defines no client wire format. */
    public GameRuntimeBuilder clientResultCodec(ClientResultCodec v) { clientResults = Objects.requireNonNull(v); return this; }
    public GameRuntimeBuilder httpHandlers(Iterable<?> v) { httpHandlers = copy(v); return this; }
    public GameRuntimeBuilder httpContextFactory(HttpContextFactory v) { httpContexts = Objects.requireNonNull(v); return this; }
    public GameRuntimeBuilder httpResultCodec(HttpResultCodec v) { httpResults = Objects.requireNonNull(v); return this; }
    public GameRuntimeBuilder httpRequestCodec(HttpRequestCodec v) { httpRequests = Objects.requireNonNull(v); return this; }
    public GameRuntimeBuilder eventHandlers(Iterable<?> v) { events = copy(v); return this; }
    public GameRuntimeBuilder cronHandlers(Iterable<?> v) { crons = copy(v); return this; }
    public GameRuntimeBuilder errorHandler(RuntimeErrorHandler v) { errors = Objects.requireNonNull(v); return this; }
    public GameRuntimeBuilder cronZone(ZoneId v) { cronZone = Objects.requireNonNull(v); return this; }
    /**
     * Whether each Route task reads the monotonic clock twice to accumulate {@code RuntimeStats.executionNanos}
     * (default true). Disable on very high task rates when that statistic is not needed; it then stays zero.
     */
    public GameRuntimeBuilder executionTiming(boolean enabled) { executionTiming = enabled; return this; }
    public GameRuntime build() {
        return RuntimeCompiler.build(new RuntimeSettings(domains, executors, protocols, keys, handlers, arguments,
                handlerContexts, clientResults, events, crons, httpHandlers, httpContexts, httpResults, httpRequests,
                cronZone, errors, executionTiming));
    }
}
