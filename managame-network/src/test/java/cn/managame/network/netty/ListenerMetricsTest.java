package cn.managame.network.netty;

import static cn.managame.network.netty.TestSupport.LOCAL;
import static cn.managame.network.netty.TestSupport.serve;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.network.connection.Connection;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.TestSupport.RecordingHandler;
import cn.managame.network.netty.TestSupport.TestClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListenerMetricsTest {
    private static final char[] PASSWORD = "changeit".toCharArray();

    /** Stands in for a backend: adds up counters, and holds gauges until they are closed. */
    private static final class Backend implements Metrics {
        final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
        final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();

        private static String key(String name, String... tags) { return name + List.of(tags); }

        @Override public Counter counter(String name, String... tags) { return counts.computeIfAbsent(key(name, tags), key -> new LongAdder())::add; }

        @Override public Meter counter(String name, LongSupplier count, String... tags) { throw new UnsupportedOperationException(); }

        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            gauges.put(key(name, tags), value);
            return () -> gauges.remove(key(name, tags));
        }

        long count(String name, String... tags) { return counts.get(key(name, tags)).sum(); }

        double gauge(String name, String... tags) { return gauges.get(key(name, tags)).getAsDouble(); }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(5);
        }
    }

    @Test void aListenerReportsItsConnectionsAndTheBytesOnTheWire() throws Exception {
        Backend metrics = new Backend();
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.tcp().handler(handler).metrics(metrics, "game"))) {
            try (TestClient client = new TestClient(server.port(), p -> {})) {
                assertEquals("active", handler.next());
                assertEquals(1, metrics.gauge("managame.network.connections.active", "listener", "game"));
                client.send(Unpooled.copiedBuffer("hello", UTF_8));
                assertEquals("hello", client.next());
                assertEquals(5, metrics.count("managame.network.bytes.read", "listener", "game"));
                assertEquals(5, metrics.count("managame.network.bytes.written", "listener", "game"));
            }
            await(() -> metrics.gauge("managame.network.connections.active", "listener", "game") == 0);
            assertEquals(1, metrics.count("managame.network.connections.opened", "listener", "game"));
        }
    }

    @Test void onlyAListenerThatReportsHasTheMetricsHandlerAndItComesFirst() throws Exception {
        AtomicReference<List<String>> names = new AtomicReference<>();
        ConnectionInitializer.Builder silent = ConnectionInitializer.tcp().handler(new RecordingHandler()).customize(p -> names.set(p.names()));
        try (NetworkServer server = serve(silent); TestClient client = new TestClient(server.port(), p -> {})) {
            await(() -> names.get() != null);
            assertFalse(names.get().contains(PipelineNames.METRICS));
        }
        names.set(null);
        ConnectionInitializer.Builder reporting = ConnectionInitializer.tcp().handler(new RecordingHandler())
                .addFront("first-of-the-application", () -> new io.netty.channel.ChannelInboundHandlerAdapter())
                .customize(p -> names.set(p.names()))
                .metrics(new Backend(), "game");
        try (NetworkServer server = serve(reporting); TestClient client = new TestClient(server.port(), p -> {})) {
            await(() -> names.get() != null);
            assertTrue(names.get().indexOf(PipelineNames.METRICS) >= 0);
            assertTrue(names.get().indexOf(PipelineNames.METRICS) < names.get().indexOf("first-of-the-application"));
        }
    }

    @SuppressWarnings("deprecation")
    @Test void aWebSocketHandshakeThatTimesOutIsCounted() {
        Backend metrics = new Backend();
        EmbeddedChannel channel = new EmbeddedChannel(ConnectionInitializer.ws("/game").handler(new RecordingHandler()).metrics(metrics, "ws").build());
        channel.pipeline().fireUserEventTriggered(WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT);
        assertEquals(1, metrics.count("managame.network.handshakes.failed", "listener", "ws"));
        channel.finishAndReleaseAll();
    }

    @Test void aListenerNeedsANameAndItsHandlerNameIsReserved() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionInitializer.tcp().metrics(new Backend(), " "));
        assertThrows(IllegalArgumentException.class, () -> ConnectionInitializer.tcp().addLast(PipelineNames.METRICS, () -> new io.netty.channel.ChannelInboundHandlerAdapter()));
    }

    @Test void exceptionsAreCountedByKind() throws Exception {
        Backend metrics = new Backend();
        RecordingHandler handler = new RecordingHandler() {
            @Override public void onRead(Connection connection, Object message) { throw new IllegalStateException("a bug"); }
        };
        try (NetworkServer server = serve(ConnectionInitializer.tcp().addLast(NetworkHandlers.lengthFieldFraming(8)).handler(handler).metrics(metrics, "game"));
             TestClient client = new TestClient(server.port(), p -> {})) {
            assertEquals("active", handler.next());
            client.send(Unpooled.buffer().writeInt(1).writeByte(7));
            assertEquals("exception:IllegalStateException", handler.next());
            assertEquals(1, metrics.count("managame.network.exceptions", "listener", "game", "kind", "other"));
            client.send(Unpooled.buffer().writeInt(100));
            assertEquals("exception:TooLongFrameException", handler.next());
            assertEquals(1, metrics.count("managame.network.exceptions", "listener", "game", "kind", "decode"));
        }
    }

    @Test void aConnectionThatTurnsBusyIsCounted() throws Exception {
        Backend metrics = new Backend();
        RecordingHandler handler = new RecordingHandler();
        ConnectionInitializer game = ConnectionInitializer.tcp().handler(handler).metrics(metrics, "game").build();
        try (NetworkServer server = NetworkServer.start(new ServerBootstrap().childHandler(game)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1, 2)), LOCAL);
             TestClient client = new TestClient(server.port(), p -> {})) {
            client.send(Unpooled.copiedBuffer("more than two bytes", UTF_8));
            await(() -> metrics.count("managame.network.connections.busy", "listener", "game") >= 1);
        }
    }

    @Test void aFailedTlsHandshakeIsCounted(@TempDir Path directory) throws Exception {
        SslContext ssl = NetworkSsl.server(TestSupport.selfSignedKeyStore(directory, PASSWORD), PASSWORD);
        Backend metrics = new Backend();
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.tcp().ssl(ssl).handler(handler).metrics(metrics, "secure"));
             TestClient client = new TestClient(server.port(), p -> {})) {
            client.send(Unpooled.copiedBuffer("this is not a TLS record at all", UTF_8));
            await(() -> metrics.count("managame.network.handshakes.failed", "listener", "secure") == 1);
        }
    }

    private static FullHttpRequest request(HttpMethod method, String uri, String body) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.copiedBuffer(body, UTF_8));
        HttpUtil.setContentLength(request, body.length());
        return request;
    }

    @Test void httpResponsesAreCountedByStatusClass() throws Exception {
        Backend metrics = new Backend();
        HttpInitializer admin = HttpInitializer.http().contextPath("/admin").maxContentLength(8)
                .handler(exchange -> exchange.respond(HttpResponseStatus.OK))
                .metrics(metrics, "admin").build();
        try (NetworkServer server = NetworkServer.start(LOCAL, admin)) {
            try (TestClient client = new TestClient(server.port(), p -> p.addLast(new HttpClientCodec(), new HttpObjectAggregator(65536)))) {
                client.send(request(HttpMethod.GET, "/admin", ""));
                assertEquals("http:200", client.next());
                client.send(request(HttpMethod.GET, "/elsewhere", ""));
                assertEquals("http:404", client.next());
                assertEquals(1, metrics.count("managame.network.http.responses", "listener", "admin", "status", "2xx"));
                assertEquals(1, metrics.count("managame.network.http.responses", "listener", "admin", "status", "4xx"));
                assertEquals(1, metrics.gauge("managame.network.connections.active", "listener", "admin"));
            }
            try (TestClient client = new TestClient(server.port(), p -> p.addLast(new HttpClientCodec(), new HttpObjectAggregator(65536)))) {
                client.send(request(HttpMethod.POST, "/admin", "longer than eight bytes"));
                assertEquals("http:413", client.next());
                assertEquals(2, metrics.count("managame.network.http.responses", "listener", "admin", "status", "4xx"));
            }
        }
    }

    @Test void eventLoopsAndDirectMemoryAreReadWhenTheBackendCollects() throws Exception {
        Backend metrics = new Backend();
        try (NetworkServer server = serve(ConnectionInitializer.tcp().handler(new RecordingHandler()))) {
            Meter loops = NetworkMetrics.eventLoops(metrics, server.eventLoops(), "game");
            Meter memory = NetworkMetrics.directMemory(metrics);
            assertEquals(0, metrics.gauge("managame.network.eventloop.pending_tasks", "group", "game"));
            assertTrue(metrics.gauge("managame.network.memory.direct_used") >= 0);
            loops.close();
            memory.close();
            assertTrue(metrics.gauges.isEmpty());
        }
    }
}
