package cn.managame.network.tests;

import cn.managame.network.netty.connection.NettyAccess;
import cn.managame.network.netty.transport.HttpNetworkServer;
import cn.managame.network.netty.transport.NetworkOptions;
import cn.managame.network.netty.transport.NetworkResources;
import cn.managame.network.netty.transport.TcpNetworkClient;
import cn.managame.network.netty.transport.TcpNetworkServer;
import cn.managame.network.netty.transport.WsNetworkClient;
import cn.managame.network.netty.transport.WsNetworkServer;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;
import cn.managame.network.netty.transport.*;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.*;
import io.netty.util.ReferenceCountUtil;

import org.junit.jupiter.api.*;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.net.ssl.KeyManagerFactory;

@Timeout(20)
class NetworkIntegrationTest {
    @Test
    void httpContextPathWorksWithAggregatedAndStreamingRequests() throws Exception {
        var shared = resources();
        for (int aggregation : new int[] {0, 1024 * 1024}) {
            for (String context : new String[] {"", "/", "/game/"}) {
                var httpServer =
                        server(
                                HttpNetworkServer.builder()
                                        .resources(shared)
                                        .listen("http", loopback())
                                        .contextPath(context)
                                        .httpAggregation(aggregation)
                                        .httpPipeline(
                                                pipeline ->
                                                        pipeline.addLast(
                                                                new ChannelInboundHandlerAdapter() {
                                                                    private String uri;

                                                                    @Override
                                                                    public void channelRead(
                                                                            ChannelHandlerContext
                                                                                    ctx,
                                                                            Object msg) {
                                                                        try {
                                                                            if (msg
                                                                                    instanceof
                                                                                    HttpRequest
                                                                                            request)
                                                                                uri = request.uri();
                                                                            if (msg
                                                                                    instanceof
                                                                                    LastHttpContent) {
                                                                                var response =
                                                                                        new DefaultFullHttpResponse(
                                                                                                HttpVersion
                                                                                                        .HTTP_1_1,
                                                                                                HttpResponseStatus
                                                                                                        .OK,
                                                                                                Unpooled
                                                                                                        .copiedBuffer(
                                                                                                                uri,
                                                                                                                StandardCharsets
                                                                                                                        .UTF_8));
                                                                                HttpUtil
                                                                                        .setContentLength(
                                                                                                response,
                                                                                                response.content()
                                                                                                        .readableBytes());
                                                                                ctx.writeAndFlush(
                                                                                        response);
                                                                            }
                                                                        } finally {
                                                                            ReferenceCountUtil
                                                                                    .release(msg);
                                                                        }
                                                                    }
                                                                })));
                String base = "http://127.0.0.1:" + port(httpServer, "http");
                String prefix = context.equals("/game/") ? "/game" : "";
                try (var http =
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                    if (!prefix.isEmpty()) {
                        var rejected =
                                java.net.http.HttpRequest.newBuilder(
                                                URI.create(base + "/game2/login"))
                                        .POST(
                                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                                        "ignored body"))
                                        .build();
                        assertEquals(
                                404,
                                http.send(rejected, HttpResponse.BodyHandlers.ofString())
                                        .statusCode());
                    }
                    for (String suffix : new String[] {"/login?next=%2Fgame%2Fa&x=1+2", "/"}) {
                        var request =
                                java.net.http.HttpRequest.newBuilder(
                                                URI.create(base + prefix + suffix))
                                        .POST(
                                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                                        "body"))
                                        .build();
                        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode());
                        assertEquals(suffix, response.body());
                    }
                }
                httpServer.stop();
            }
        }
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    private final List<Runnable> cleanup = new ArrayList<>();
    private final Queue<Throwable> diagnostics = new ConcurrentLinkedQueue<>();

    private NetworkResources resources() {
        var r =
                NetworkResources.builder()
                        .ioThreads(2)
                        .httpThreads(1)
                        .diagnostics((m, t) -> diagnostics.add(t))
                        .build();
        cleanup.add(r::close);
        return r;
    }

    private TcpNetworkServer server(TcpNetworkServer.Builder b) {
        var s = b.build();
        cleanup.add(s::stop);
        s.start();
        return s;
    }

    private TcpNetworkClient client(TcpNetworkClient.Builder b) {
        var c = b.build();
        cleanup.add(c::destroy);
        c.init();
        return c;
    }

    private WsNetworkServer server(WsNetworkServer.Builder b) {
        var s = b.build();
        cleanup.add(s::stop);
        s.start();
        return s;
    }

    private HttpNetworkServer server(HttpNetworkServer.Builder b) {
        var s = b.build();
        cleanup.add(s::stop);
        s.start();
        return s;
    }

    private WsNetworkClient client(WsNetworkClient.Builder b) {
        var c = b.build();
        cleanup.add(c::destroy);
        c.init();
        return c;
    }

    private static int port(WsNetworkServer s, String name) {
        return s.boundAddresses().get(name).getPort();
    }

    private static int port(HttpNetworkServer s, String name) {
        return s.boundAddresses().get(name).getPort();
    }

    @AfterEach
    void cleanup() {
        Throwable first = null;
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try {
                cleanup.get(i).run();
            } catch (Throwable t) {
                if (first == null) first = t;
                else first.addSuppressed(t);
            }
        }
        if (first != null) throw new AssertionError("Cleanup failed", first);
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        return f.get(8, TimeUnit.SECONDS);
    }

    private static ConnectCallback callback(CompletableFuture<Connection> f) {
        return new ConnectCallback() {
            public void onSuccess(Connection c) {
                f.complete(c);
            }

            public void onFailure(Throwable t) {
                f.completeExceptionally(t);
            }
        };
    }

    private static Connection connect(TcpNetworkClient c, int port) throws Exception {
        var f = new CompletableFuture<Connection>();
        c.connect("127.0.0.1", port, callback(f));
        return await(f);
    }

    private static Connection connect(WsNetworkClient c, URI uri) throws Exception {
        var f = new CompletableFuture<Connection>();
        c.connect(uri, callback(f));
        return await(f);
    }

    private static NetworkHandler echo() {
        return (c, m) -> {
            ReferenceCountUtil.retain(m);
            if (!c.write(m)) ReferenceCountUtil.release(m);
        };
    }

    private static void framing(ChannelPipeline p) {
        p.addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4), new LengthFieldPrepender(4));
    }

    private static TcpNetworkServer.Builder tcp(NetworkResources r) {
        return TcpNetworkServer.builder()
                .resources(r)
                .handlerFactory(NetworkIntegrationTest::echo)
                .pipeline((c, p) -> framing(p))
                .listen("tcp", loopback());
    }

    private static int port(TcpNetworkServer s, String name) {
        return s.boundAddresses().get(name).getPort();
    }

    @Test
    void tcpMaintainsMessageOrderAndReferenceOwnership() throws Exception {
        var r = resources();
        var accepted = new CompletableFuture<Connection>();
        var s =
                server(
                        tcp(r).pipeline(
                                        (x, p) -> {
                                            framing(p);
                                            accepted.complete(x);
                                        }));
        int count = 100;
        var received = new ArrayList<Integer>();
        var finished = new CompletableFuture<Void>();
        var inbound = new AtomicReference<ByteBuf>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .pipeline((x, p) -> framing(p))
                                .handlerFactory(
                                        () ->
                                                (x, m) -> {
                                                    ByteBuf buf = (ByteBuf) m;
                                                    inbound.set(buf);
                                                    received.add(buf.readInt());
                                                    if (received.size() == count)
                                                        finished.complete(null);
                                                }));
        Connection connection = connect(c, port(s, "tcp"));
        assertEquals(ConnectionType.TCP, connection.type());
        assertEquals(ConnectionType.TCP, await(accepted).type());
        for (int i = 0; i < count; i++)
            assertTrue(connection.write(Unpooled.buffer(4).writeInt(i)));
        await(finished);
        c.destroy();
        assertEquals(java.util.stream.IntStream.range(0, count).boxed().toList(), received);
        assertEquals(0, inbound.get().refCnt());
        ByteBuf rejected = Unpooled.buffer(1).writeByte(1);
        assertFalse(connection.write(rejected));
        assertEquals(1, rejected.refCnt());
        rejected.release();
        assertFalse(r.isClosed());
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @Test
    void oneClientConnectsToMultipleTcpTargetsWithDistinctIds() throws Exception {
        var r = resources();
        var a = server(tcp(r));
        var b = server(tcp(r));
        var c = client(TcpNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        var f1 = new CompletableFuture<Connection>();
        var f2 = new CompletableFuture<Connection>();
        c.connect("127.0.0.1", port(a, "tcp"), callback(f1));
        c.connect("127.0.0.1", port(b, "tcp"), callback(f2));
        var ca = await(f1);
        var cb = await(f2);
        assertNotEquals(ca.id(), cb.id());
        assertEquals(port(a, "tcp"), ((InetSocketAddress) ca.remoteAddress()).getPort());
        assertEquals(port(b, "tcp"), ((InetSocketAddress) cb.remoteAddress()).getPort());
        a.stop();
        assertTrue(cb.isActive());
    }

    @Test
    void protocolServersShareResourcesWithIndependentLifecycles() throws Exception {
        var r = resources();
        var tcpServer = server(tcp(r));
        var wsServer =
                server(
                        WsNetworkServer.builder()
                                .resources(r)
                                .listen("ws", loopback())
                                .handlerFactory(NetworkIntegrationTest::echo)
                                .webSocketServer(b -> b.websocketPath("/game")));
        var httpServer =
                server(HttpNetworkServer.builder().resources(r).listen("http", loopback()));
        var received = new CompletableFuture<String>();
        var disconnected = new CompletableFuture<Void>();
        var c =
                client(
                        WsNetworkClient.builder()
                                .resources(r)
                                .handlerFactory(
                                        () ->
                                                new NetworkHandler() {
                                                    public void onMessage(Connection x, Object m) {
                                                        received.complete(
                                                                ((TextWebSocketFrame) m).text());
                                                    }

                                                    public void onDisconnected(Connection x) {
                                                        disconnected.complete(null);
                                                    }
                                                }));
        Connection ws = connect(c, URI.create("ws://127.0.0.1:" + port(wsServer, "ws") + "/game"));
        tcpServer.stop();
        assertTrue(ws.write(new TextWebSocketFrame("hello")));
        assertEquals("hello", await(received));
        wsServer.stop();
        await(disconnected);
        assertFalse(ws.isActive());
        try (var http = HttpClient.newHttpClient()) {
            var request =
                    java.net.http.HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port(httpServer, "http")
                                                    + "/missing"))
                            .build();
            assertEquals(
                    404, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertFalse(r.isClosed());
    }

    @Test
    void nativeHttpHandlerUsesSeparateEventLoop() throws Exception {
        var r = resources();
        var tcpThread = new AtomicReference<Thread>();
        var httpThread = new AtomicReference<Thread>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var s =
                server(
                        tcp(r).handlerFactory(
                                        () ->
                                                new NetworkHandler() {
                                                    public void onConnected(Connection c) {
                                                        tcpThread.set(Thread.currentThread());
                                                    }

                                                    public void onMessage(Connection c, Object m) {
                                                        ReferenceCountUtil.retain(m);
                                                        if (!c.write(m))
                                                            ReferenceCountUtil.release(m);
                                                    }
                                                }));
        var httpServer =
                server(
                        HttpNetworkServer.builder()
                                .resources(r)
                                .listen("http", loopback())
                                .httpPipeline(
                                        p ->
                                                p.addLast(
                                                        new SimpleChannelInboundHandler<
                                                                FullHttpRequest>() {
                                                            protected void channelRead0(
                                                                    ChannelHandlerContext ctx,
                                                                    FullHttpRequest request)
                                                                    throws Exception {
                                                                httpThread.set(
                                                                        Thread.currentThread());
                                                                entered.countDown();
                                                                if (!release.await(
                                                                        5, TimeUnit.SECONDS))
                                                                    throw new IllegalStateException(
                                                                            "Test timed out");
                                                                var response =
                                                                        new DefaultFullHttpResponse(
                                                                                HttpVersion
                                                                                        .HTTP_1_1,
                                                                                HttpResponseStatus
                                                                                        .OK,
                                                                                Unpooled
                                                                                        .copiedBuffer(
                                                                                                "ok",
                                                                                                StandardCharsets
                                                                                                        .UTF_8));
                                                                HttpUtil.setContentLength(
                                                                        response, 2);
                                                                ctx.writeAndFlush(response);
                                                            }
                                                        })));
        var got = new CompletableFuture<Integer>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .pipeline((x, p) -> framing(p))
                                .handlerFactory(
                                        () -> (x, m) -> got.complete(((ByteBuf) m).readInt())));
        Connection connection = connect(c, port(s, "tcp"));
        try (var http = HttpClient.newHttpClient()) {
            var request =
                    java.net.http.HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:" + port(httpServer, "http") + "/"))
                            .build();
            var response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                connection.write(Unpooled.buffer(4).writeInt(42));
                assertEquals(42, await(got));
                assertNotEquals(tcpThread.get(), httpThread.get());
                assertTrue(httpThread.get().getName().startsWith("game-http"));
            } finally {
                release.countDown();
            }
            assertEquals("ok", await(response).body());
        }
    }

    @Test
    void bridgeForwardsNativeInboundExceptions() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var events = new CopyOnWriteArrayList<String>();
        var ended = new CompletableFuture<Void>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .pipeline((x, p) -> framing(p))
                                .handlerFactory(
                                        () ->
                                                new NetworkHandler() {
                                                    public void onConnected(Connection x) {
                                                        events.add("connected");
                                                    }

                                                    public void onMessage(Connection x, Object m) {
                                                        events.add("message");
                                                        throw new IllegalArgumentException(
                                                                "business-error");
                                                    }

                                                    public void onException(
                                                            Connection x, Throwable t) {
                                                        events.add("exception");
                                                        x.close();
                                                    }

                                                    public void onDisconnected(Connection x) {
                                                        events.add("disconnected");
                                                        ended.complete(null);
                                                    }
                                                }));
        var connection = connect(c, port(s, "tcp"));
        connection.write(Unpooled.buffer(4).writeInt(1));
        await(ended);
        assertEquals(List.of("connected", "message", "exception", "disconnected"), events);
    }

    @Test
    void unifiedIdleAndNativeAttributes() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var key = AttributeKey.of("user", String.class);
        var idle = new CompletableFuture<IdleType>();
        var ended = new CompletableFuture<Void>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .option(NetworkOptions.READ_IDLE, Duration.ofMillis(50))
                                .handlerFactory(
                                        () ->
                                                new NetworkHandler() {
                                                    public void onConnected(Connection x) {
                                                        x.set(key, "player");
                                                    }

                                                    public void onMessage(Connection x, Object m) {}

                                                    public void onIdle(
                                                            Connection x, IdleType type) {
                                                        idle.complete(type);
                                                        x.close();
                                                    }

                                                    public void onDisconnected(Connection x) {
                                                        assertEquals("player", x.get(key));
                                                        ended.complete(null);
                                                    }
                                                }));
        Connection connection = connect(c, port(s, "tcp"));
        assertEquals(IdleType.READ, await(idle));
        await(ended);
        c.destroy();
        assertEquals("player", connection.remove(key));
        connection.set(key, "late");
        assertEquals("late", connection.get(key));
    }

    @Test
    void changingBuildersDoesNotAffectBuiltComponents() throws Exception {
        var r = resources();
        var serverNoDelay = new CompletableFuture<Boolean>();
        var reply = new CompletableFuture<String>();
        var serverBuilder =
                WsNetworkServer.builder()
                        .resources(r)
                        .listen("ws", loopback())
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.TCP_NODELAY, false)
                        .option(NetworkOptions.WS_AGGREGATION, 1024)
                        .webSocketServer(config -> config.websocketPath("/original"))
                        .handlerFactory(
                                () ->
                                        new NetworkHandler() {
                                            public void onConnected(Connection c) {
                                                serverNoDelay.complete(
                                                        NettyAccess.channel(c)
                                                                .config()
                                                                .getOption(
                                                                        ChannelOption.TCP_NODELAY));
                                            }

                                            public void onMessage(Connection c, Object message) {
                                                c.write(
                                                        new TextWebSocketFrame(
                                                                "reply:"
                                                                        + ((TextWebSocketFrame)
                                                                                        message)
                                                                                .text()));
                                            }
                                        });
        var server = serverBuilder.build();
        cleanup.add(server::stop);
        var clientBuilder =
                WsNetworkClient.builder()
                        .resources(r)
                        .option(ChannelOption.TCP_NODELAY, false)
                        .webSocketClient(config -> config.maxFramePayloadLength(4096))
                        .handlerFactory(
                                () ->
                                        (c, message) ->
                                                reply.complete(
                                                        ((TextWebSocketFrame) message).text()));
        var client = clientBuilder.build();
        cleanup.add(client::destroy);

        serverBuilder
                .childOption(ChannelOption.TCP_NODELAY, true)
                .option(NetworkOptions.WS_AGGREGATION, 1)
                .webSocketServer(config -> config.websocketPath("/changed"))
                .handlerFactory(() -> (c, message) -> c.close())
                .pipeline(
                        (c, p) -> {
                            throw new AssertionError("Mutated server pipeline");
                        });
        clientBuilder
                .option(ChannelOption.TCP_NODELAY, true)
                .webSocketClient(config -> config.maxFramePayloadLength(1))
                .handlerFactory(() -> (c, message) -> c.close())
                .pipeline(
                        (c, p) -> {
                            throw new AssertionError("Mutated client pipeline");
                        });

        server.start();
        client.init();
        Connection connection =
                connect(client, URI.create("ws://127.0.0.1:" + port(server, "ws") + "/original"));
        assertFalse(await(serverNoDelay));
        assertFalse(NettyAccess.channel(connection).config().getOption(ChannelOption.TCP_NODELAY));
        connection.write(new TextWebSocketFrame(false, 0, "first"));
        connection.write(new ContinuationWebSocketFrame(true, 0, "second"));
        assertEquals("reply:firstsecond", await(reply));
    }

    @Test
    void clientLifecycleIsIndependentOfIndividualConnections() throws Exception {
        var r = resources();
        var tcpServer = server(tcp(r));
        var wsServer =
                server(
                        WsNetworkServer.builder()
                                .resources(r)
                                .listen("ws", loopback())
                                .handlerFactory(NetworkIntegrationTest::echo));
        var tcpClient =
                TcpNetworkClient.builder().resources(r).handlerFactory(() -> (c, m) -> {}).build();
        var wsClient =
                WsNetworkClient.builder().resources(r).handlerFactory(() -> (c, m) -> {}).build();
        cleanup.add(tcpClient::destroy);
        cleanup.add(wsClient::destroy);
        assertClientLifecycle(
                tcpClient, cb -> tcpClient.connect("127.0.0.1", port(tcpServer, "tcp"), cb));
        assertClientLifecycle(
                wsClient,
                cb ->
                        wsClient.connect(
                                URI.create("ws://127.0.0.1:" + port(wsServer, "ws") + "/"), cb));
        assertFalse(r.isClosed(), "Destroying clients must preserve borrowed resources");
    }

    private static void assertClientLifecycle(
            NetworkClient client, java.util.function.Consumer<ConnectCallback> connect)
            throws Exception {
        var beforeInit = new CompletableFuture<Connection>();
        assertThrows(NetworkException.class, () -> connect.accept(callback(beforeInit)));
        assertFalse(beforeInit.isDone());
        client.init();
        client.init();
        var first = new CompletableFuture<Connection>();
        connect.accept(callback(first));
        Connection connection = await(first);
        connection.close();
        assertTrue(NettyAccess.channel(connection).closeFuture().await(5, TimeUnit.SECONDS));
        var second = new CompletableFuture<Connection>();
        connect.accept(callback(second));
        Connection anotherConnection = await(second);
        assertTrue(anotherConnection.isActive());
        client.destroy();
        client.destroy();
        assertFalse(anotherConnection.isActive());
        assertThrows(
                NetworkException.class, () -> connect.accept(callback(new CompletableFuture<>())));
        assertThrows(NetworkException.class, client::init);
    }

    @Test
    void destroyingClientBeforeInitializationIsTerminal() {
        var r = resources();
        NetworkClient tcp =
                TcpNetworkClient.builder().resources(r).handlerFactory(() -> (c, m) -> {}).build();
        NetworkClient ws =
                WsNetworkClient.builder().resources(r).handlerFactory(() -> (c, m) -> {}).build();
        for (var client : List.of(tcp, ws)) {
            cleanup.add(client::destroy);
            client.destroy();
            assertThrows(NetworkException.class, client::init);
        }
        assertFalse(r.isClosed());
    }

    @Test
    void synchronousLifecycleCannotRunInsideCallback() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var checked = new CompletableFuture<Void>();
        var ref = new AtomicReference<TcpNetworkClient>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .handlerFactory(
                                        () ->
                                                new NetworkHandler() {
                                                    public void onConnected(Connection x) {
                                                        try {
                                                            assertThrows(
                                                                    IllegalStateException.class,
                                                                    () -> ref.get().init());
                                                            assertThrows(
                                                                    IllegalStateException.class,
                                                                    () -> ref.get().destroy());
                                                            assertThrows(
                                                                    IllegalStateException.class,
                                                                    s::stop);
                                                            assertThrows(
                                                                    IllegalStateException.class,
                                                                    r::close);
                                                            checked.complete(null);
                                                        } catch (Throwable t) {
                                                            checked.completeExceptionally(t);
                                                        }
                                                    }

                                                    public void onMessage(Connection x, Object m) {}
                                                }));
        ref.set(c);
        connect(c, port(s, "tcp"));
        await(checked);
    }

    @Test
    void startupRollsBackAllPreviouslyBoundListeners() throws Exception {
        var r = resources();
        try (var occupied = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var s =
                    TcpNetworkServer.builder()
                            .resources(r)
                            .handlerFactory(NetworkIntegrationTest::echo)
                            .listen("first", loopback())
                            .listen(
                                    "occupied",
                                    new InetSocketAddress("127.0.0.1", occupied.getLocalPort()))
                            .build();
            cleanup.add(s::stop);
            assertThrows(NetworkException.class, s::start);
            int first = port(s, "first");
            try (var rebound =
                    new java.net.ServerSocket(first, 1, InetAddress.getByName("127.0.0.1"))) {
                assertEquals(first, rebound.getLocalPort());
            }
            assertFalse(r.isClosed());
        }
    }

    @Test
    void onConnectedFailureReportsOriginalCauseOnceAndClosesTcpAndWs() throws Exception {
        var r = resources();
        var tcpServer = server(tcp(r));
        var wsServer =
                server(
                        WsNetworkServer.builder()
                                .resources(r)
                                .listen("ws", loopback())
                                .handlerFactory(NetworkIntegrationTest::echo));
        for (boolean websocket : new boolean[] {false, true}) {
            var cause = new IllegalStateException("Session initialization failed");
            var observed = new CompletableFuture<Connection>();
            var failure = new CompletableFuture<Throwable>();
            var results = new AtomicInteger();
            var disconnected = new AtomicInteger();
            NetworkHandler handler =
                    new NetworkHandler() {
                        public void onConnected(Connection c) {
                            observed.complete(c);
                            throw cause;
                        }

                        public void onMessage(Connection c, Object message) {
                            fail("Failed connection must not receive business messages");
                        }

                        public void onDisconnected(Connection c) {
                            disconnected.incrementAndGet();
                        }
                    };
            ConnectCallback callback =
                    new ConnectCallback() {
                        public void onSuccess(Connection c) {
                            results.incrementAndGet();
                            failure.completeExceptionally(new AssertionError("Unexpected success"));
                        }

                        public void onFailure(Throwable problem) {
                            results.incrementAndGet();
                            failure.complete(problem);
                        }
                    };
            if (websocket) {
                var c =
                        client(
                                WsNetworkClient.builder()
                                        .resources(r)
                                        .handlerFactory(() -> handler));
                c.connect(URI.create("ws://127.0.0.1:" + port(wsServer, "ws") + "/"), callback);
            } else {
                var c =
                        client(
                                TcpNetworkClient.builder()
                                        .resources(r)
                                        .handlerFactory(() -> handler));
                c.connect("127.0.0.1", port(tcpServer, "tcp"), callback);
            }
            assertSame(cause, await(failure));
            var channel = NettyAccess.channel(await(observed));
            assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS));
            channel.eventLoop().submit(() -> {}).sync();
            assertFalse(channel.isActive());
            assertEquals(1, results.get());
            assertEquals(0, disconnected.get());
        }
    }

    @Test
    void clientsReportPipelineInitializationFailureWithOriginalCause() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var cause = new IllegalStateException("Decoder configuration failed");
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .handlerFactory(() -> (x, m) -> {})
                                .pipeline(
                                        (x, p) -> {
                                            throw cause;
                                        }));
        var result = new CompletableFuture<Connection>();
        c.connect("127.0.0.1", port(s, "tcp"), callback(result));
        assertSame(cause, assertThrows(ExecutionException.class, () -> await(result)).getCause());
        var ws =
                client(
                        WsNetworkClient.builder()
                                .resources(r)
                                .handlerFactory(() -> (x, m) -> {})
                                .pipeline(
                                        (x, p) -> {
                                            throw cause;
                                        }));
        var wsResult = new CompletableFuture<Connection>();
        ws.connect(URI.create("ws://127.0.0.1:" + port(s, "tcp") + "/"), callback(wsResult));
        assertSame(cause, assertThrows(ExecutionException.class, () -> await(wsResult)).getCause());
    }

    @Test
    void clientsReportChannelCreationFailureBeforePipelineExists() throws Exception {
        var cause = new IllegalStateException("Channel creation failed");
        var r =
                NetworkResources.builder()
                        .ioThreads(1)
                        .transport(
                                NioIoHandler.newFactory(),
                                io.netty.channel.socket.nio.NioServerSocketChannel::new,
                                () -> {
                                    throw cause;
                                },
                                io.netty.channel.socket.nio.NioDatagramChannel::new)
                        .build();
        cleanup.add(r::close);
        var c = client(TcpNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        var result = new CompletableFuture<Connection>();
        c.connect("127.0.0.1", 1, callback(result));
        assertSame(cause, assertThrows(ExecutionException.class, () -> await(result)).getCause());
        c.destroy();
        var ws = client(WsNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        var wsResult = new CompletableFuture<Connection>();
        ws.connect(URI.create("ws://127.0.0.1:1/"), callback(wsResult));
        assertSame(cause, assertThrows(ExecutionException.class, () -> await(wsResult)).getCause());
        ws.destroy();
        assertFalse(r.isClosed());
    }

    @Test
    void pendingHandshakeIsFailedExactlyOnceByDestroy() throws Exception {
        var r = resources();
        var s = server(tcp(r).pipeline((x, p) -> {}).handlerFactory(() -> (x, m) -> {}));
        var c = client(WsNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        var results = new AtomicInteger();
        var failure = new CompletableFuture<Throwable>();
        var uri = URI.create("ws://127.0.0.1:" + port(s, "tcp") + "/");
        c.connect(
                uri,
                new ConnectCallback() {
                    public void onSuccess(Connection x) {
                        results.incrementAndGet();
                        failure.completeExceptionally(new AssertionError("Unexpected success"));
                    }

                    public void onFailure(Throwable t) {
                        results.incrementAndGet();
                        failure.complete(t);
                    }
                });
        c.destroy();
        assertNotNull(await(failure));
        assertEquals(1, results.get());
        assertThrows(
                NetworkException.class, () -> c.connect(uri, callback(new CompletableFuture<>())));
    }

    @Test
    void nativeHandshakeTimeoutDoesNotPreventSubsequentConnections() throws Exception {
        var r = resources();
        var accepted = new CountDownLatch(1);
        var s =
                server(
                        tcp(r).pipeline((x, p) -> {})
                                .handlerFactory(
                                        () ->
                                                new NetworkHandler() {
                                                    public void onConnected(Connection x) {
                                                        accepted.countDown();
                                                    }

                                                    public void onMessage(Connection x, Object m) {}
                                                }));
        var wsServer =
                server(
                        WsNetworkServer.builder()
                                .resources(r)
                                .listen("ws", loopback())
                                .handlerFactory(NetworkIntegrationTest::echo));
        var c =
                client(
                        WsNetworkClient.builder()
                                .resources(r)
                                .handlerFactory(() -> (x, m) -> {})
                                .webSocketClient(config -> config.handshakeTimeoutMillis(500)));
        var first = new CompletableFuture<Connection>();
        var uri = URI.create("ws://127.0.0.1:" + port(wsServer, "ws") + "/");
        c.connect(URI.create("ws://127.0.0.1:" + port(s, "tcp") + "/"), callback(first));
        assertTrue(accepted.await(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> await(first));
        assertTrue(connect(c, uri).isActive());
    }

    private record GameMessage(int sequence, String text) {}

    private static void tcpMessageCodec(ChannelPipeline pipeline) {
        framing(pipeline);
        pipeline.addLast(
                "game.codec",
                new MessageToMessageCodec<ByteBuf, GameMessage>() {
                    @Override
                    protected void encode(
                            ChannelHandlerContext ctx, GameMessage message, List<Object> out) {
                        ByteBuf buffer = ctx.alloc().buffer();
                        buffer.writeInt(message.sequence());
                        buffer.writeCharSequence(message.text(), StandardCharsets.UTF_8);
                        out.add(buffer);
                    }

                    @Override
                    protected void decode(
                            ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) {
                        out.add(
                                new GameMessage(
                                        buffer.readInt(), buffer.toString(StandardCharsets.UTF_8)));
                    }
                });
    }

    private static void wsMessageCodec(ChannelPipeline pipeline) {
        pipeline.addLast(
                "game.codec",
                new MessageToMessageCodec<TextWebSocketFrame, GameMessage>() {
                    @Override
                    protected void encode(
                            ChannelHandlerContext ctx, GameMessage message, List<Object> out) {
                        out.add(new TextWebSocketFrame(message.sequence() + ":" + message.text()));
                    }

                    @Override
                    protected void decode(
                            ChannelHandlerContext ctx, TextWebSocketFrame frame, List<Object> out) {
                        String[] fields = frame.text().split(":", 2);
                        out.add(new GameMessage(Integer.parseInt(fields[0]), fields[1]));
                    }
                });
    }

    @Test
    void userTcpCodecExchangesBusinessObjectsInBothDirections() throws Exception {
        var r = resources();
        var received = new CompletableFuture<GameMessage>();
        var s =
                server(
                        tcp(r).pipeline((c, p) -> tcpMessageCodec(p))
                                .handlerFactory(
                                        () ->
                                                (c, message) -> {
                                                    GameMessage request = (GameMessage) message;
                                                    c.write(
                                                            new GameMessage(
                                                                    request.sequence(),
                                                                    "reply:" + request.text()));
                                                }));
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .pipeline((connection, p) -> tcpMessageCodec(p))
                                .handlerFactory(
                                        () ->
                                                (connection, message) ->
                                                        received.complete((GameMessage) message)));
        Connection connection = connect(c, port(s, "tcp"));
        var names = NettyAccess.channel(connection).pipeline().names();
        assertTrue(names.indexOf("game.codec") < names.indexOf("network.handler"));
        assertNull(NettyAccess.channel(connection).pipeline().get("network.basicEncoding"));
        assertTrue(connection.write(new GameMessage(42, "登录")));
        assertEquals(new GameMessage(42, "reply:登录"), await(received));
    }

    @Test
    void userWsCodecExchangesBusinessObjectsAndOptionalAggregationHandlesFragments()
            throws Exception {
        var r = resources();
        var replies = new LinkedBlockingQueue<GameMessage>();
        var s =
                server(
                        WsNetworkServer.builder()
                                .resources(r)
                                .listen("ws", loopback())
                                .option(NetworkOptions.WS_AGGREGATION, 1024)
                                .pipeline((c, p) -> wsMessageCodec(p))
                                .handlerFactory(
                                        () ->
                                                (c, message) -> {
                                                    GameMessage request = (GameMessage) message;
                                                    c.write(
                                                            new GameMessage(
                                                                    request.sequence(),
                                                                    "reply:" + request.text()));
                                                }));
        var c =
                client(
                        WsNetworkClient.builder()
                                .resources(r)
                                .pipeline((connection, p) -> wsMessageCodec(p))
                                .handlerFactory(
                                        () ->
                                                (connection, message) ->
                                                        replies.add((GameMessage) message)));
        Connection connection = connect(c, URI.create("ws://127.0.0.1:" + port(s, "ws") + "/"));
        var pipeline = NettyAccess.channel(connection).pipeline();
        var names = pipeline.names();
        assertTrue(names.indexOf("network.websocket") < names.indexOf("game.codec"));
        assertTrue(names.indexOf("game.codec") < names.indexOf("network.handler"));
        assertNull(pipeline.get("network.websocketAggregate"));
        assertTrue(connection.write(new GameMessage(42, "登录")));
        assertEquals(new GameMessage(42, "reply:登录"), replies.poll(5, TimeUnit.SECONDS));
        connection.write(new TextWebSocketFrame(false, 0, "43:frag"));
        connection.write(new ContinuationWebSocketFrame(true, 0, "ment"));
        assertEquals(new GameMessage(43, "reply:fragment"), replies.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void connectionRefusalCallsFailureOnce() throws Exception {
        var r = resources();
        int unused;
        try (var socket = new java.net.ServerSocket(0)) {
            unused = socket.getLocalPort();
        }
        var c = client(TcpNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        var result = new CompletableFuture<Connection>();
        var count = new AtomicInteger();
        c.connect(
                "127.0.0.1",
                unused,
                new ConnectCallback() {
                    public void onSuccess(Connection x) {
                        count.incrementAndGet();
                        result.complete(x);
                    }

                    public void onFailure(Throwable t) {
                        count.incrementAndGet();
                        result.completeExceptionally(t);
                    }
                });
        assertThrows(ExecutionException.class, () -> await(result));
        c.destroy();
        assertEquals(1, count.get());
    }

    @Test
    void borrowedEventLoopRemainsUsableAfterResourceClose() throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        cleanup.add(() -> group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly());
        var r = NetworkResources.builder().ioGroup(group).ioThreads(1).httpThreads(1).build();
        cleanup.add(r::close);
        var s = server(tcp(r));
        var c = client(TcpNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        connect(c, port(s, "tcp"));
        c.destroy();
        s.stop();
        r.close();
        assertFalse(group.isShuttingDown());
        assertEquals(7, group.next().submit(() -> 7).get(5, TimeUnit.SECONDS));
    }

    @Test
    void nativeEncoderSupportsCustomMessageWithoutRegistration() throws Exception {
        record Message(int value) {}
        var r = resources();
        var s = server(tcp(r));
        var got = new CompletableFuture<Integer>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .pipeline(
                                        (x, p) -> {
                                            framing(p);
                                            p.addLast(
                                                    new MessageToByteEncoder<Message>(
                                                            Message.class) {
                                                        protected void encode(
                                                                ChannelHandlerContext ctx,
                                                                Message m,
                                                                ByteBuf out) {
                                                            out.writeInt(m.value());
                                                        }
                                                    });
                                        })
                                .handlerFactory(
                                        () -> (x, m) -> got.complete(((ByteBuf) m).readInt())));
        var connection = connect(c, port(s, "tcp"));
        assertTrue(connection.write(new Message(73)));
        assertEquals(73, await(got));
        assertTrue(
                NettyAccess.editPipeline(
                                connection,
                                p ->
                                        p.addBefore(
                                                "network.handler",
                                                "custom",
                                                new ChannelInboundHandlerAdapter()))
                        .await(5, TimeUnit.SECONDS));
        assertNotNull(NettyAccess.channel(connection).pipeline().get("custom"));
        var invalid = NettyAccess.editPipeline(connection, p -> p.remove("custom"));
        assertTrue(invalid.await(5, TimeUnit.SECONDS));
        assertTrue(invalid.isSuccess());
        assertTrue(connection.isActive());
    }

    @Test
    void destroyTimeoutWaitsForNativeCloseFuture() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .option(NetworkOptions.DESTROY_TIMEOUT, Duration.ofMillis(100))
                                .pipeline(
                                        (x, p) ->
                                                p.addLast(
                                                        new ChannelOutboundHandlerAdapter() {
                                                            @Override
                                                            public void close(
                                                                    ChannelHandlerContext ctx,
                                                                    ChannelPromise promise) {
                                                                entered.countDown();
                                                                release.whenComplete(
                                                                        (v, t) ->
                                                                                ctx.executor()
                                                                                        .execute(
                                                                                                () ->
                                                                                                        ctx
                                                                                                                .close(
                                                                                                                        promise)));
                                                            }
                                                        }))
                                .handlerFactory(() -> (x, m) -> {}));
        var connection = connect(c, port(s, "tcp"));
        connection.close();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(NetworkException.class, c::destroy);
        } finally {
            release.complete(null);
        }
        NettyAccess.channel(connection).closeFuture().sync();
        c.destroy();
    }

    @Test
    void disconnectExceptionFollowsNativePipeline() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var observed = new CompletableFuture<Throwable>();
        var c =
                client(
                        TcpNetworkClient.builder()
                                .resources(r)
                                .handlerFactory(
                                        () ->
                                                new NetworkHandler() {
                                                    public void onMessage(Connection x, Object m) {}

                                                    public void onDisconnected(Connection x) {
                                                        throw new IllegalStateException(
                                                                "disconnect-error");
                                                    }

                                                    public void onException(
                                                            Connection x, Throwable t) {
                                                        observed.complete(t);
                                                    }
                                                }));
        var connection = connect(c, port(s, "tcp"));
        c.destroy();
        assertEquals("disconnect-error", await(observed).getMessage());
        assertFalse(connection.isActive());
    }

    @Test
    void acceptedConnectsEventuallyReportOnceWhenDestroyed() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var c = client(TcpNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        var results = new AtomicIntegerArray(50);
        var completed = new CountDownLatch(results.length());
        for (int i = 0; i < results.length(); i++) {
            final int index = i;
            c.connect(
                    "127.0.0.1",
                    port(s, "tcp"),
                    new ConnectCallback() {
                        public void onSuccess(Connection x) {
                            results.incrementAndGet(index);
                            completed.countDown();
                        }

                        public void onFailure(Throwable t) {
                            results.incrementAndGet(index);
                            completed.countDown();
                        }
                    });
        }
        c.destroy();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < results.length(); i++) assertEquals(1, results.get(i), "attempt " + i);
    }

    @Test
    void writesRacingCloseAlwaysSettleOwnedReferences() throws Exception {
        var r = resources();
        var s = server(tcp(r));
        var c = client(TcpNetworkClient.builder().resources(r).handlerFactory(() -> (x, m) -> {}));
        var connection = connect(c, port(s, "tcp"));
        var buffers = new ConcurrentLinkedQueue<ByteBuf>();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++)
                jobs.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    for (int i = 0; i < 100; i++) {
                                        var message = Unpooled.buffer(4).writeInt(i);
                                        buffers.add(message);
                                        if (!connection.write(message)) message.release();
                                    }
                                    return null;
                                }));
            start.countDown();
            connection.close();
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        c.destroy();
        for (var buffer : buffers) assertEquals(0, buffer.refCnt());
    }

    @Test
    void wssHandshakeAndHostnameVerification() throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = getClass().getResourceAsStream("/localhost-test.p12")) {
            assertNotNull(input);
            store.load(input, "changeit".toCharArray());
        }
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        var serverTls = SslContextBuilder.forServer(kmf).sslProvider(SslProvider.JDK).build();
        var clientTls =
                SslContextBuilder.forClient()
                        .sslProvider(SslProvider.JDK)
                        .trustManager((X509Certificate) store.getCertificate("localhost"))
                        .build();
        var r = resources();
        var secureAccepted = new CompletableFuture<Connection>();
        var plainAccepted = new CompletableFuture<Connection>();
        var s =
                server(
                        WsNetworkServer.builder()
                                .resources(r)
                                .handlerFactory(NetworkIntegrationTest::echo)
                                .listen("wss", loopback())
                                .pipeline((x, p) -> secureAccepted.complete(x))
                                .sslContext(serverTls));
        var plain =
                server(
                        WsNetworkServer.builder()
                                .resources(r)
                                .handlerFactory(NetworkIntegrationTest::echo)
                                .pipeline((x, p) -> plainAccepted.complete(x))
                                .listen("ws", loopback()));
        var got = new LinkedBlockingQueue<String>();
        var c =
                client(
                        WsNetworkClient.builder()
                                .resources(r)
                                .sslContext(clientTls)
                                .handlerFactory(
                                        () -> (x, m) -> got.add(((TextWebSocketFrame) m).text())));
        var secure = new CompletableFuture<Connection>();
        var insecure = new CompletableFuture<Connection>();
        c.connect(URI.create("wss://localhost:" + port(s, "wss") + "/"), callback(secure));
        c.connect(URI.create("ws://127.0.0.1:" + port(plain, "ws") + "/"), callback(insecure));
        assertEquals(ConnectionType.WSS, await(secure).type());
        assertEquals(ConnectionType.WS, await(insecure).type());
        assertEquals(ConnectionType.WSS, await(secureAccepted).type());
        assertEquals(ConnectionType.WS, await(plainAccepted).type());
        await(secure).write(new TextWebSocketFrame("tls"));
        await(insecure).write(new TextWebSocketFrame("plain"));
        assertEquals(
                Set.of("tls", "plain"),
                Set.of(got.poll(5, TimeUnit.SECONDS), got.poll(5, TimeUnit.SECONDS)));
        var bad = new CompletableFuture<Connection>();
        c.connect(URI.create("wss://127.0.0.1:" + port(s, "wss") + "/"), callback(bad));
        assertThrows(ExecutionException.class, () -> await(bad));
    }
}
