package cn.managame.spring.runtime;

import cn.managame.network.http.HttpServer;
import cn.managame.network.http.HttpResponseCallback;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.spring.httpfixture.EchoHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpAssemblyTest {
    @Configuration(proxyBeanMethods = false)
    @EnableGameRuntime(basePackages = "cn.managame.spring.httpfixture")
    static class Config {
        @Bean(destroyMethod = "close") RouteExecutor routeExecutor() { return RouteExecutors.virtualThreads(); }
        @Bean GameRuntimeConfigurer configurer(RouteExecutor executor) {
            return builder -> builder.routeDomains(List.of(RouteDomain.of(1, "http")))
                    .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1)));
        }
    }

    static class Application implements AutoCloseable {
        final AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        final NioEventLoopGroup boss = new NioEventLoopGroup(1), worker = new NioEventLoopGroup(1);
        Application(Map<String, Object> properties) {
            var values = new HashMap<String, Object>(Map.of("game.http.port", "0"));
            values.putAll(properties);
            spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("http-test", values));
            spring.registerBean(GameHttpConfigurer.class, () -> builder -> builder.bossGroup(boss).workerGroup(worker));
            spring.register(Config.class);
        }
        void refresh() { spring.refresh(); }
        int port() { return ((InetSocketAddress) spring.getBean(HttpServer.class).localAddress()).getPort(); }
        String base() { return "http://127.0.0.1:" + port(); }
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
            Path marker = Path.of("target/http-tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Test-only TCP Selector wakeup pipe");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    @Test void automaticallyStartsScannedHandlersAndMapsContextPathWithoutChangingRoutes() throws Exception {
        try (var app = new Application(Map.of("game.http.context-path", "/game/", "game.http.max-content-length", "64"));
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()) {
            app.refresh();
            assertNotNull(app.spring.getBean(EchoHandler.class));
            String body = "{\"id\":7,\"text\":\"你好\"}";
            var response = post(client, app.base() + "/game/echo", body);
            assertEquals(200, response.statusCode());
            var json = new ObjectMapper().readTree(response.body());
            assertEquals("你好", json.path("text").asText());
            assertEquals(7L, json.path("key").asLong()); assertTrue(json.path("virtual").asBoolean());
            assertEquals("/echo", json.path("uri").asText());
            response = post(client, app.base() + "/game/raw", body);
            assertEquals(body, new ObjectMapper().readTree(response.body()).path("text").asText());
            response = get(client, app.base() + "/game/query?id=8&text=hello");
            assertEquals(200, response.statusCode());
            json = new ObjectMapper().readTree(response.body());
            assertEquals(8L, json.path("key").asLong()); assertEquals("hello", json.path("text").asText());
            assertEquals("/query?id=8&text=hello", json.path("uri").asText());
            assertEquals(200, get(client, app.base() + "/game?id=9").statusCode());
            assertEquals(404, post(client, app.base() + "/echo", body).statusCode());
            assertEquals(404, post(client, app.base() + "/games/echo", body).statusCode());
            assertEquals(404, post(client, app.base() + "/game%2Fecho", body).statusCode());
            assertEquals(405, get(client, app.base() + "/game/echo?id=7").statusCode());
            assertEquals(400, post(client, app.base() + "/game/echo", "{\"text\":\"no key\"}").statusCode());
            assertEquals(413, post(client, app.base() + "/game/echo", "x".repeat(65)).statusCode());
        }
    }

    @Test void shutdownRejectsNewWorkBeforeClosingListenerAndWaitsForAdmittedHandler() throws Exception {
        try (var app = new Application(Map.of());
             var first = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
             var second = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            app.refresh(); int port = app.port();
            var handler = app.spring.getBean(EchoHandler.class);
            var runtime = app.spring.getBean(GameRuntime.class);
            var pending = first.sendAsync(HttpRequest.newBuilder(URI.create(app.base() + "/wait"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":1,\"text\":\"done\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(handler.entered.await(3, TimeUnit.SECONDS));
                var closing = threads.submit(app.spring::close);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (runtime.stats().accepting() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(runtime.stats().accepting()); assertFalse(closing.isDone());
                assertEquals(503, post(second, app.base() + "/echo", "{\"id\":2,\"text\":\"reject\"}").statusCode());
                handler.release.countDown(); closing.get(5, TimeUnit.SECONDS);
                // Drain is execution completion, not a promise that a racing socket write was delivered.
                pending.handle((_, _) -> null).get(3, TimeUnit.SECONDS);
                assertFalse(app.spring.getBeanFactory().containsSingleton("gameHttpServer"));
                try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", port)); }
            } finally { handler.release.countDown(); }
        }
    }

    @Test void disabledHttpDoesNotBindEvenWithHandlers() {
        try (var app = new Application(Map.of("game.http.enabled", "false"))) {
            app.refresh();
            assertNull(app.spring.getBean(HttpServer.class).localAddress());
            assertTrue(app.spring.getBean(GameRuntime.class).stats().accepting());
        }
    }

    @Test void noHttpHandlersLeavesListenerUnboundByDefault() {
        try (var spring = new AnnotationConfigApplicationContext(RuntimeLifecycleTest.Config.class)) {
            assertNull(spring.getBean(HttpServer.class).localAddress());
        }
    }

    @Test void anUnrelatedPrimaryHttpBeanDoesNotReplaceTheManagedListener() {
        try (var app = new Application(Map.of())) {
            app.spring.registerBean("otherHttpServer", HttpServer.class,
                    () -> HttpServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0)).build(),
                    definition -> definition.setPrimary(true));
            app.refresh();
            assertNull(app.spring.getBean("otherHttpServer", HttpServer.class).localAddress());
            assertNotNull(app.spring.getBean("gameHttpServer", HttpServer.class).localAddress());
        }
    }

    @Test void invalidPropertiesAndBindFailureReleaseRuntimeAndDoNotLeaveAListener() throws Exception {
        for (var properties : List.of(Map.<String, Object>of("game.http.port", "65536"),
                Map.<String, Object>of("game.http.context-path", "/api/../other"),
                Map.<String, Object>of("game.http.max-header-size", "0"),
                Map.<String, Object>of("game.http.read-timeout-millis", "-1"))) {
            try (var app = new Application(properties)) { assertThrows(RuntimeException.class, app::refresh); }
        }
        try (var reserved = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var app = new Application(Map.of("game.http.port", Integer.toString(reserved.getLocalPort())))) {
            var created = new AtomicReference<GameRuntime>();
            app.spring.addBeanFactoryPostProcessor(factory -> factory.addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof GameRuntime runtime) created.set(runtime);
                    return bean;
                }
            }));
            assertThrows(RuntimeException.class, app::refresh);
            assertNotNull(created.get()); assertFalse(created.get().stats().accepting());
        }
        try (var restarted = new Application(Map.of())) { restarted.refresh(); assertTrue(restarted.port() > 0); }
    }

    @Test void prefixMappingPreservesOriginalRequestAndReferenceOwnershipOnFailure() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/query?id=7");
        var response = new HttpResponseCallback() {
            public boolean onResponse(FullHttpResponse response) { response.release(); return true; }
            public boolean onFail(Throwable cause) { throw new AssertionError(cause); }
        };
        try {
            var mapping = new HttpContextPath("/api", (mapped, _) -> {
                assertEquals("/query?id=7", mapped.uri());
                assertEquals("/api/query?id=7", request.uri());
                throw new IllegalStateException("delegate failed");
            });
            assertThrows(IllegalStateException.class, () -> mapping.dispatch(request, response));
            assertEquals(1, request.refCnt()); assertEquals("/api/query?id=7", request.uri());
        } finally { request.release(); }
        for (String invalid : List.of("api", "/api//v1", "/api//", "/api?x", "/api#x", "/api/%2f", "/./api"))
            assertThrows(IllegalArgumentException.class, () -> new HttpContextPath(invalid, (_, _) -> {}));
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
