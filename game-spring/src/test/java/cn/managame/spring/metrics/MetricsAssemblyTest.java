package cn.managame.spring.metrics;

import cn.managame.boot.metrics.ReadinessCheck;
import cn.managame.core.metrics.MetricsRegistry;
import cn.managame.network.http.HttpServer;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.spring.runtime.EnableGameRuntime;
import cn.managame.spring.runtime.GameHttpConfigurer;
import cn.managame.spring.runtime.GameRuntimeConfigurer;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class MetricsAssemblyTest {
    @Configuration(proxyBeanMethods = false)
    @EnableGameRuntime
    @EnableGameMetrics
    static class Config {
        @Bean(destroyMethod = "close") RouteExecutor routeExecutor() { return RouteExecutors.virtualThreads(); }
        @Bean GameRuntimeConfigurer configurer(RouteExecutor executor) {
            return builder -> builder.routeDomains(List.of(RouteDomain.of(1, "metrics")))
                    .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1)));
        }
    }
    /** An application's own condition, contributed as an ordinary Bean. */
    static final class Gate implements ReadinessCheck {
        volatile boolean open = true;
        @Override public String name() { return "demo.world.loaded"; }
        @Override public boolean ready() { return open; }
    }

    static final class Application implements AutoCloseable {
        final AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        final NioEventLoopGroup boss = new NioEventLoopGroup(1), worker = new NioEventLoopGroup(1);
        final Gate gate = new Gate();
        Application(Map<String, Object> properties) {
            var values = new HashMap<String, Object>(Map.of("game.http.port", "0", "game.http.enabled", "true"));
            values.putAll(properties);
            spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("metrics-test", values));
            spring.registerBean(GameHttpConfigurer.class, () -> builder -> builder.bossGroup(boss).workerGroup(worker));
            spring.registerBean(ReadinessCheck.class, () -> gate);
            spring.register(Config.class);
        }
        void refresh() { spring.refresh(); }
        String base() {
            return "http://127.0.0.1:" + ((InetSocketAddress) spring.getBean(HttpServer.class).localAddress()).getPort();
        }
        @Override public void close() {
            try { spring.close(); }
            finally {
                boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
                worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }

    @BeforeAll static void selectorPipe() throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/metrics-tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Test-only TCP Selector wakeup pipe");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }
    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void publishesThePresentComponentsAndServesBothPathsOutsideTheContextPath() throws Exception {
        try (var app = new Application(Map.of("game.http.context-path", "/game"));
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            app.refresh();
            // Runtime is present; Data, RPC and a client listener are not, so only runtime is published.
            assertEquals(List.of("runtime"), app.spring.getBean(MetricsRegistry.class).prefixes());

            var metrics = get(client, app.base() + "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.headers().firstValue("content-type").orElse("").startsWith("text/plain"));
            Map<String, Long> readings = new HashMap<>();
            for (String line : metrics.body().split("\n")) {
                String[] parts = line.split(" ");
                readings.put(parts[0], Long.parseLong(parts[1]));
            }
            assertEquals(1L, readings.get("runtime.accepting"));
            assertEquals(0L, readings.get("runtime.route.queued"));
            assertTrue(readings.containsKey("runtime.route.completed"), readings.keySet().toString());
            // The one registered Domain is published under its id, and a name segment must be letter-led,
            // so the id follows "domain" rather than standing alone.
            assertEquals(0L, readings.get("runtime.domain1.queued"));
            assertTrue(readings.containsKey("runtime.domain1.rejected"), readings.keySet().toString());
            // Its executor's configured bound travels with it; without that, queued has no denominator.
            assertEquals(65536L, readings.get("runtime.domain1.executor.capacity"));

            var ready = get(client, app.base() + "/readiness");
            assertEquals(200, ready.statusCode());
            assertEquals("ready\n", ready.body());

            // The application's own check makes the instance not ready, and is named in the body.
            app.gate.open = false;
            var notReady = get(client, app.base() + "/readiness");
            assertEquals(503, notReady.statusCode());
            assertEquals("not ready: demo.world.loaded\n", notReady.body());
            app.gate.open = true;

            // Both paths are absolute: the context path neither reaches nor hides them.
            assertEquals(404, get(client, app.base() + "/game/metrics").statusCode());
            assertEquals(404, get(client, app.base() + "/game/readiness").statusCode());
        }
    }
    @Test void aDrainingRuntimeIsNotReadyAndThePathsAreConfigurable() throws Exception {
        try (var app = new Application(Map.of("game.metrics.path", "/stats", "game.metrics.readiness-path", "/ready"));
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            app.refresh();
            assertEquals(200, get(client, app.base() + "/stats").statusCode());
            assertEquals(200, get(client, app.base() + "/ready").statusCode());
            assertEquals(404, get(client, app.base() + "/metrics").statusCode());

            // Shutting down Runtime admission is exactly what a drain does; readiness must report it.
            app.spring.getBean(GameRuntime.class).shutdown();
            var draining = get(client, app.base() + "/ready");
            assertEquals(503, draining.statusCode());
            assertTrue(draining.body().contains("runtime.accepting"), draining.body());
            // Metrics still answer while draining, and say the same thing.
            assertTrue(get(client, app.base() + "/stats").body().contains("runtime.accepting 0"));
        }
    }
    @Test void theLeadReportsUnreadinessWhileWorkIsStillAdmitted() throws Exception {
        // The whole point of the lead: a load balancer can learn to stop sending before anything is refused.
        try (var app = new Application(Map.of("game.shutdown.budget-millis", "20000",
                "game.shutdown.lead-millis", "1500", "game.shutdown.flush-reserve-millis", "5000"));
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            app.refresh();
            GameRuntime runtime = app.spring.getBean(GameRuntime.class);
            assertEquals(200, get(client, app.base() + "/readiness").statusCode());
            assertTrue(runtime.stats().accepting());

            Thread closing = new Thread(app.spring::close, "closing");
            closing.start();
            try {
                // Poll until readiness turns, then check admission in the same breath.
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                HttpResponse<String> response = null;
                while (System.nanoTime() < end) {
                    response = get(client, app.base() + "/readiness");
                    if (response.statusCode() == 503) break;
                    Thread.sleep(5);
                }
                assertNotNull(response);
                assertEquals(503, response.statusCode(), "readiness never reported failure during the lead");
                assertTrue(response.body().contains("shutdown.rotation"), response.body());
                // This is the assertion the finding was about: unready, yet still accepting work.
                assertTrue(runtime.stats().accepting(), "admission closed before the lead elapsed");
            } finally { closing.join(10_000); }
            assertFalse(runtime.stats().accepting());    // and closed once the lead elapsed
        }
    }
}
