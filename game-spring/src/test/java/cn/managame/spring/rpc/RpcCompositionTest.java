package cn.managame.spring.rpc;

import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.runtime.context.*;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.protocol.*;
import cn.managame.runtime.route.*;
import cn.managame.spring.runtime.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** GameRpcConfigurer composition hooks used by routing integrations such as GameRouter/ServiceRouting. */
class RpcCompositionTest {
    record Request(String text) {}

    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent()); Files.writeString(marker, "test-only TCP fallback");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    /** Records hook order and forwards every message to the managed Runtime handler. */
    static final class Recorder implements GameRpcConfigurer, org.springframework.core.Ordered {
        final String name;
        final int order;
        final List<String> events;
        final BlockingQueue<Integer> wrapped = new LinkedBlockingQueue<>();
        Recorder(String name, int order, List<String> events) { this.name = name; this.order = order; this.events = events; }
        public int getOrder() { return order; }
        public void configure(cn.managame.rpc.node.RpcNodeBuilder builder) { events.add(name + ":configure"); }
        public RpcHandler decorate(RpcHandler handler) {
            events.add(name + ":decorate");
            return new RpcHandler() {
                public void onRequest(int source, int slot, RpcRequest request) {
                    wrapped.add(request.command()); handler.onRequest(source, slot, request);
                }
                public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
                    handler.onResponse(source, command, response, callback);
                }
                public void onFail(int target, int command, int error, RpcCallback<?> callback) {
                    handler.onFail(target, command, error, callback);
                }
            };
        }
        public void attach(RpcNode node) { events.add(name + ":attach:" + (node.localAddress() == null ? "idle" : "listening")); }
        public void detach(RpcNode node) { events.add(name + ":detach:" + (node.localAddress() == null ? "idle" : "listening")); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableGameRuntime(basePackages = "cn.managame.spring.rpcfixture")
    @EnableGameRpc
    static class Config {
        static final List<String> EVENTS = new CopyOnWriteArrayList<>();
        @Bean RouteExecutor executor() { return RouteExecutors.virtualThreads(); }
        @Bean GameRuntimeConfigurer runtimeConfigurer(RouteExecutor executor) {
            return builder -> builder.routeDomains(List.of(RouteDomain.of(1, "role")))
                    .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1)));
        }
        @Bean ProtocolProvider protocols() { return registry -> registry.register(Protocols.request(1, Request.class)); }
        @Bean GameRpcCodec codec() { return new GameRpcCodec() {
            public byte[] encode(Object message) { return ((Request) message).text().getBytes(StandardCharsets.UTF_8); }
            public <T> T decode(byte[] body, Class<T> type) { return type.cast(new Request(new String(body, StandardCharsets.UTF_8))); }
        }; }
        @Bean Recorder second() { return new Recorder("second", 2, EVENTS); }
        @Bean Recorder first() { return new Recorder("first", 1, EVENTS); }
        @Bean Handlers handlers() { return new Handlers(); }
    }

    @Handler(domain = 1)
    static class Handlers {
        final BlockingQueue<RpcHandlerContext> received = new LinkedBlockingQueue<>();
        @HandlerMethod public void request(Request message) { received.add(Contexts.current(RpcHandlerContext.class)); }
    }

    @Test void decoratedHandlerReachesRuntimeAndHooksFollowLifecycleOrder() throws Exception {
        Config.EVENTS.clear();
        RpcHandler ignore = new RpcHandler() {
            public void onRequest(int source, int slot, RpcRequest request) {}
            public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {}
            public void onFail(int target, int command, int error, RpcCallback<?> callback) {}
        };
        RpcNode remote = RpcNode.builder().nodeId(2).bindAddress(new InetSocketAddress("127.0.0.1", 0)).handler(ignore).build();
        remote.start();
        try (remote) {
            var context = new AnnotationConfigApplicationContext();
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rpc", Map.of(
                    "game.rpc.node-id", 1, "game.rpc.port", 0, "game.http.enabled", false)));
            context.register(Config.class);
            context.refresh();
            try (context) {
                assertEquals(List.of("first:configure", "second:configure", "first:decorate", "second:decorate",
                        "first:attach:idle", "second:attach:idle"), Config.EVENTS);
                context.getBean(RpcNode.class).addPeer(2, remote.localAddress(), 1);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (remote.notify(1, new RpcRequest(1, 77, 0, 0, null,
                        Unpooled.wrappedBuffer("hi".getBytes(StandardCharsets.UTF_8)))) != RpcSendStatus.ACCEPTED) {
                    assertTrue(System.nanoTime() < deadline); Thread.sleep(5);
                }
                // Both decorators delegate; the Runtime handler stays innermost.
                assertEquals(1, context.getBean("second", Recorder.class).wrapped.poll(5, TimeUnit.SECONDS));
                assertEquals(1, context.getBean("first", Recorder.class).wrapped.poll(5, TimeUnit.SECONDS));
                RpcHandlerContext incoming = context.getBean(Handlers.class).received.poll(5, TimeUnit.SECONDS);
                assertNotNull(incoming);
                assertEquals(77, incoming.routeKey());
            }
            assertEquals(List.of("second:detach:listening", "first:detach:listening"),
                    Config.EVENTS.subList(Config.EVENTS.size() - 2, Config.EVENTS.size()));
        }
    }

    @Test void failedAttachDetachesEarlierConfigurersAndClosesTheNode() {
        List<String> events = new ArrayList<>();
        GameRpcConfigurer failing = new GameRpcConfigurer() {
            public void configure(cn.managame.rpc.node.RpcNodeBuilder builder) {}
            public void attach(RpcNode node) { throw new IllegalStateException("attach failed"); }
        };
        var builder = RpcNode.builder().nodeId(1).bindAddress(new InetSocketAddress("127.0.0.1", 0));
        var runtime = cn.managame.runtime.GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "role")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1))).build();
        try (runtime) {
            var error = assertThrows(IllegalStateException.class, () -> new GameRpc(runtime, new GameRpcCodec() {
                public byte[] encode(Object message) { return new byte[0]; }
                public <T> T decode(byte[] body, Class<T> type) { throw new UnsupportedOperationException(); }
            }, builder, List.of(new Recorder("ok", 0, events), failing)));
            assertEquals("attach failed", error.getMessage());
        }
        assertEquals(List.of("ok:decorate", "ok:attach:idle", "ok:detach:idle"), events);
    }
}
