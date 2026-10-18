package cn.managame.runtime.internal;

import static cn.managame.runtime.internal.Reflection.invalid;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.http.DefaultHttpContext;
import cn.managame.runtime.internal.RuntimeBindings.Compiled;
import cn.managame.runtime.internal.RuntimeBindings.HandlerBinding;
import cn.managame.runtime.internal.RuntimeBindings.HttpBinding;
import cn.managame.runtime.internal.RuntimeBindings.HttpEndpoint;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds an immutable Runtime: validates Domains and executors, then delegates each binding kind to its
 * compiler (Handler, Event/Cron, HTTP). Internal implementation, not an application API.
 */
public final class RuntimeCompiler {
    private RuntimeCompiler() {}

    public static GameRuntime build(RuntimeSettings settings) {
        Set<Integer> domains = new HashSet<>();
        for (var d : settings.domains()) if (!domains.add(d.id())) throw invalid("Duplicate domain " + d.id());
        Map<Integer, RouteExecutor> routes = routes(settings, domains);

        HandlerCompiler handlers = new HandlerCompiler(domains, settings.protocols(), settings.routeKeys(), settings.handlerArguments());
        for (Object target : settings.handlers()) handlers.compile(target);
        if (settings.clientResults() == null && handlers.handlers.values().stream().anyMatch(HandlerBinding::returnsResult))
            throw invalid("Handlers that return a client response require clientResultCodec");
        var events = EventCompiler.events(settings.eventHandlers());
        var crons = EventCompiler.crons(settings.cronHandlers(), domains, settings.cronZone());
        Map<HttpEndpoint, HttpBinding> http = HttpCompiler.compile(settings.httpHandlers(), domains, settings.httpRequests());
        if (settings.httpContexts() == null) {
            if (http.values().stream().anyMatch(binding -> binding.routeKey() == null))
                throw invalid("HTTP endpoints without a RouteKey rule require httpContextFactory");
            if (http.values().stream().anyMatch(binding -> binding.contextType() != null
                    && !binding.contextType().isAssignableFrom(DefaultHttpContext.class)))
                throw invalid("Custom HTTP contexts require httpContextFactory");
        }
        return new DefaultGameRuntime(new Compiled(Map.copyOf(routes), settings.protocols(), Map.copyOf(handlers.routeKeys),
                Map.copyOf(handlers.handlers), settings.handlerContexts(), settings.clientResults(), events, crons, http,
                settings.httpContexts(), settings.httpResults(), settings.errors(), settings.executionTiming()));
    }

    /** Every registered Domain binds exactly one executor; executors may be shared. */
    private static Map<Integer, RouteExecutor> routes(RuntimeSettings settings, Set<Integer> domains) {
        Map<Integer, RouteExecutor> routes = new HashMap<>();
        for (var binding : settings.executors()) {
            for (int domain : binding.routeDomains()) {
                if (!domains.contains(domain)) throw invalid("Executor binds unknown domain " + domain);
                if (routes.putIfAbsent(domain, binding.executor()) != null) throw invalid("Duplicate executor binding " + domain);
            }
        }
        if (!routes.keySet().equals(domains)) throw invalid("Every domain needs exactly one executor");
        return routes;
    }
}
