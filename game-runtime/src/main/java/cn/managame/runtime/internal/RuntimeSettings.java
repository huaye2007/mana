package cn.managame.runtime.internal;

import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.handler.ClientResultCodec;
import cn.managame.runtime.handler.HandlerArgumentBinding;
import cn.managame.runtime.handler.HandlerContextFactory;
import cn.managame.runtime.http.HttpContextFactory;
import cn.managame.runtime.http.HttpRequestCodec;
import cn.managame.runtime.http.HttpResultCodec;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.route.RouteKeyBinding;
import java.time.ZoneId;
import java.util.List;

/**
 * Immutable snapshot of a GameRuntimeBuilder. Internal implementation, not an application API.
 *
 * @param handlerContexts optional client context factory, or null
 * @param clientResults optional client response codec, or null when no Handler answers a client request
 * @param httpContexts optional HTTP context factory, or null
 * @param executionTiming whether every Route task measures its execution time for RuntimeStats
 */
public record RuntimeSettings(
        List<RouteDomain> domains,
        List<RouteExecutorBinding> executors,
        ProtocolRegistry protocols,
        List<RouteKeyBinding<?>> routeKeys,
        List<Object> handlers,
        List<HandlerArgumentBinding<?>> handlerArguments,
        HandlerContextFactory handlerContexts,
        ClientResultCodec clientResults,
        List<Object> eventHandlers,
        List<Object> cronHandlers,
        List<Object> httpHandlers,
        HttpContextFactory httpContexts,
        HttpResultCodec httpResults,
        HttpRequestCodec httpRequests,
        ZoneId cronZone,
        RuntimeErrorHandler errors,
        boolean executionTiming) {}
