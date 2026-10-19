package cn.managame.boot.metrics;

import cn.managame.boot.http.HttpContextPath;
import cn.managame.core.metrics.MetricsRegistry;
import cn.managame.network.http.HttpResponseCallback;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.http.HttpDispatcher;
import cn.managame.runtime.route.RouteDomain;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two endpoints answered without a listener and without a container: the composition tier decides what
 * is published and what "ready" means, and an integration only decides where the dispatcher is installed.
 */
class ObservabilityEndpointsTest {
    /** One reply, captured; the real listener writes it, this records what would have been written. */
    static final class Reply implements HttpResponseCallback {
        FullHttpResponse response;
        @Override public boolean onResponse(FullHttpResponse response) { this.response = response; return true; }
        @Override public boolean onFail(Throwable cause) { throw new AssertionError(cause); }
        int status() { return response.status().code(); }
        String body() { return response.content().toString(CharsetUtil.UTF_8); }
    }

    private static Reply get(HttpDispatcher dispatcher, String uri) { return send(dispatcher, HttpMethod.GET, uri); }

    private static Reply send(HttpDispatcher dispatcher, HttpMethod method, String uri) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
        var reply = new Reply();
        try { dispatcher.dispatch(request, reply); } finally { request.release(); }
        return reply;
    }

    /** Business dispatch that simply says it was reached, so "falls through" is observable. */
    private static HttpDispatcher business(AtomicInteger reached) {
        return (request, callback) -> {
            reached.incrementAndGet();
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            callback.onResponse(response);
        };
    }

    private static ReadinessCheck check(String name, java.util.function.BooleanSupplier ready) {
        return new ReadinessCheck() {
            @Override public String name() { return name; }
            @Override public boolean ready() { return ready.getAsBoolean(); }
        };
    }

    @Test void pathsMustBeAbsoluteAndDistinct() {
        MetricsRegistry registry = new MetricsRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> new ObservabilityEndpoints(registry, List.of(), "metrics", "/readiness"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObservabilityEndpoints(registry, List.of(), "/metrics", "/metrics"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObservabilityEndpoints(registry, List.of(), "/metrics?x", "/readiness"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObservabilityEndpoints(registry, List.of(), "/", "/readiness"));
    }

    @Test void readinessNamesEveryFailingConditionAndAThrowingOneCountsAsNotReady() {
        var world = new java.util.concurrent.atomic.AtomicBoolean(true);
        var endpoints = new ObservabilityEndpoints(new MetricsRegistry(),
                List.of(check("demo.world.loaded", world::get), check("demo.hostile", () -> {
                    throw new IllegalStateException("cannot answer");
                })), "/metrics", "/readiness");
        var reached = new AtomicInteger();
        HttpDispatcher dispatcher = endpoints.wrap(business(reached));

        // A check that cannot answer is not ready, and it is named like any other failing condition.
        var hostileOnly = get(dispatcher, "/readiness");
        assertEquals(503, hostileOnly.status());
        assertEquals("not ready: demo.hostile\n", hostileOnly.body());

        world.set(false);
        var both = get(dispatcher, "/readiness");
        assertEquals(503, both.status());
        // Order follows the list, so a reader sees the conditions in the order the application declared.
        assertEquals("not ready: demo.world.loaded, demo.hostile\n", both.body());
        assertEquals(0, reached.get(), "an observability path must never reach business dispatch");
    }

    @Test void readingsCarryTheirComponentPrefixAndAnswerWhileTheRuntimeIsDraining() {
        GameRuntime runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "role")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1))).build();
        try (runtime) {
            var registry = new MetricsRegistry();
            registry.publish("runtime", FrameworkMetrics.of(runtime));
            assertEquals(List.of("runtime"), registry.prefixes());
            var endpoints = new ObservabilityEndpoints(registry,
                    List.of(check("runtime.accepting", () -> runtime.stats().accepting())),
                    "/metrics", "/readiness");
            HttpDispatcher dispatcher = endpoints.wrap(business(new AtomicInteger()));

            assertEquals(200, get(dispatcher, "/readiness").status());
            Map<String, Long> readings = readings(get(dispatcher, "/metrics").body());
            assertEquals(1L, readings.get("runtime.accepting"));
            assertEquals(0L, readings.get("runtime.route.queued"));
            // The one registered Domain carries its executor's bound, so queued has a denominator.
            assertEquals(0L, readings.get("runtime.domain1.queued"));
            assertEquals(65536L, readings.get("runtime.domain1.executor.capacity"));
            // Nothing is running, so the stuck-Route readings are all zero rather than absent: a dashboard
            // that plots them sees a flat line instead of a gap it has to interpret.
            assertEquals(0L, readings.get("runtime.running.oldest.nanos"));
            assertEquals(0L, readings.get("runtime.running.oldest.domain"));
            assertEquals(0L, readings.get("runtime.running.oldest.key"));
            assertEquals(0L, readings.get("runtime.domain1.running.oldest.key"));

            // Closing admission is what a drain does; readiness must report it while readings keep answering.
            runtime.shutdown();
            var draining = get(dispatcher, "/readiness");
            assertEquals(503, draining.status());
            assertTrue(draining.body().contains("runtime.accepting"), draining.body());
            assertEquals(0L, readings(get(dispatcher, "/metrics").body()).get("runtime.accepting"));
        }
    }

    @Test void thePathsAreAbsoluteAndTheBusinessContextPathNeitherReachesNorHidesThem() {
        var reached = new AtomicInteger();
        var endpoints = new ObservabilityEndpoints(new MetricsRegistry(), List.of(), "/metrics", "/readiness");
        // The order an integration installs them in: observability first, the context path behind it.
        HttpDispatcher dispatcher = endpoints.wrap(new HttpContextPath("/game", business(reached)));

        assertEquals(200, get(dispatcher, "/readiness").status());
        assertEquals(0, reached.get());
        // Under the prefix they are ordinary business paths, and business does not serve them.
        assertEquals(200, get(dispatcher, "/game/readiness").status());
        assertEquals(1, reached.get());
        // A query is ignored when matching the path.
        assertEquals(200, get(dispatcher, "/metrics?format=text").status());
        assertEquals(1, reached.get());
        // Only GET is answered here. Anything else is ordinary business traffic and goes to whatever is
        // installed behind, which here is the context path refusing a path outside its prefix.
        assertEquals(404, send(dispatcher, HttpMethod.POST, "/metrics").status());
        assertEquals(1, reached.get());
        // With nothing in the way, the same request reaches business dispatch.
        assertEquals(200, send(endpoints.wrap(business(reached)), HttpMethod.POST, "/metrics").status());
        assertEquals(2, reached.get());
    }

    private static Map<String, Long> readings(String body) {
        var readings = new HashMap<String, Long>();
        for (String line : body.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(" ");
            readings.put(parts[0], Long.parseLong(parts[1]));
        }
        return readings;
    }

    @Test void everyRegisteredSourceIsReadInPrefixOrder() {
        var registry = new MetricsRegistry();
        registry.publish("first", sink -> sink.gauge("value", 1));
        registry.publish("second", sink -> sink.counter("value", 2));
        var endpoints = new ObservabilityEndpoints(registry, List.of(), "/metrics", "/readiness");
        var lines = new ArrayList<String>();
        for (String line : get(endpoints.wrap(business(new AtomicInteger())), "/metrics").body().split("\n"))
            if (!line.isBlank()) lines.add(line);
        assertEquals(List.of("first.value 1", "second.value 2"), lines);
    }
}
