package cn.managame.demo;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import cn.managame.core.FrameworkErrorCodes;
import cn.managame.demo.bus.system.DemoTasks;
import cn.managame.demo.bus.system.SystemHttpHandler;
import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.network.http.HttpServer;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.TimerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.http.HttpContext;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.timer.TimerRef;
import cn.managame.spring.runtime.GameHttpConfigurer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.FullHttpRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DemoServicesTest {
    record Invocation(int domain, long key, boolean virtual) {}
    record TimerObservation(DemoTasks.Status status, TimerContext context, boolean virtual) {}

    @Profile("manual-http-probe-only")
    static class Probe extends SystemHttpHandler {
        final LinkedBlockingQueue<Invocation> received = new LinkedBlockingQueue<>();
        Probe(DemoTasks tasks) { super(tasks); }
        @Override @HttpMethod("/demo/echo")
        public EchoResult echo(String body) {
            var context = Contexts.current(HttpContext.class);
            received.add(new Invocation(context.routeDomain(), context.routeKey(), Thread.currentThread().isVirtual()));
            return super.echo(body);
        }
    }

    @BeforeAll static void environment() throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/demo-http-tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Test-only: use TCP for the Selector wakeup pipe");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    @Test void springHttpTimerAndCronRunThroughTheConfiguredRoutes() throws Exception {
        var boss = new NioEventLoopGroup(1);
        var worker = new NioEventLoopGroup(1);
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean("systemHttpHandler", SystemHttpHandler.class,
                    () -> new Probe(spring.getBean(DemoTasks.class)));
            spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("http-test",
                    Map.of("game.http.enabled", "true", "game.http.port", "0", "game.http.context-path", "/game")));
            spring.registerBean(GameHttpConfigurer.class, () -> builder -> builder.bossGroup(boss).workerGroup(worker));
            spring.register(GameRuntimeConfig.class);
            spring.refresh();
            var runtime = spring.getBean(GameRuntime.class);
            var tasks = spring.getBean(DemoTasks.class);
            var probe = (Probe) spring.getBean(SystemHttpHandler.class);
            var observation = new DefaultPromise<TimerObservation>(GlobalEventExecutor.INSTANCE);
            runtime.timer().schedule(GameDomain.SYSTEM_ID, DemoTasks.ROUTE_KEY, Duration.ofSeconds(12), () ->
                    observation.trySuccess(new TimerObservation(tasks.status(),
                            Contexts.current(TimerContext.class), Thread.currentThread().isVirtual())));
            var server = spring.getBean(HttpServer.class);
            try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                         .connectTimeout(Duration.ofSeconds(5)).build()) {
                int port = ((InetSocketAddress) server.localAddress()).getPort();
                String base = "http://127.0.0.1:" + port + "/game";
                String body = "{\"routeKey\":99,\"message\":\"你好\"}";
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/demo/echo"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var json = new ObjectMapper().readTree(response.body());
                assertEquals(99L, json.path("routeKey").asLong()); assertEquals(body, json.path("body").asText());
                assertEquals(new Invocation(GameDomain.SYSTEM_ID, 99L, true), probe.received.poll(5, TimeUnit.SECONDS));
                response = client.send(HttpRequest.newBuilder(URI.create(base + "/demo/echo?routeKey=1")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(405, response.statusCode());
                response = client.send(HttpRequest.newBuilder(URI.create(base + "/demo/tasks")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode());
                TimerObservation completed = observation.get(15, TimeUnit.SECONDS);
                assertEquals(1L, completed.status().timerRuns()); assertTrue(completed.status().cronRuns() >= 1L);
                assertEquals(GameDomain.SYSTEM_ID, completed.context().routeDomain());
                assertEquals(DemoTasks.ROUTE_KEY, completed.context().routeKey()); assertTrue(completed.virtual());
                response = client.send(HttpRequest.newBuilder(URI.create(base + "/demo/tasks?routeKey=1")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                json = new ObjectMapper().readTree(response.body());
                assertEquals(1L, json.path("timerRuns").asLong()); assertTrue(json.path("cronRuns").asLong() >= 1L);
            }
        } finally {
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test void springClosureCancelsThePendingStartupTimerAndClosesCron() {
        var spring = new AnnotationConfigApplicationContext();
        spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("timer-test",
                Map.of("game.demo.timer.delayMillis", "3600000")));
        spring.register(GameRuntimeConfig.class);
        try {
            spring.refresh();
            var runtime = spring.getBean(GameRuntime.class);
            var timer = spring.getBean("demoStartupTimer", TimerRef.class);
            var tasks = spring.getBean(DemoTasks.class);
            assertTrue(runtime.cron().cancel(DemoTasks.class, "onCron"));
            assertFalse(runtime.cron().cancel(DemoTasks.class, "onCron"));
            assertTrue(runtime.cron().reschedule(DemoTasks.class, "onCron"));
            spring.close();
            assertFalse(timer.cancel()); assertEquals(0L, tasks.status().timerRuns());
            assertEquals(FrameworkErrorCodes.RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.timer().schedule(GameDomain.SYSTEM_ID, 1L, Duration.ZERO, () -> fail("closed timer"))).errorCode());
            assertEquals(FrameworkErrorCodes.RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.cron().reschedule(DemoTasks.class, "onCron")).errorCode());
        } finally { spring.close(); }
    }
}
