package cn.managame.rpc.netty;

import cn.managame.network.netty.connection.NettyAccess;
import cn.managame.network.netty.transport.NetworkResources;
import cn.managame.rpc.core.RpcDiagnostic;
import cn.managame.rpc.core.RpcHandler;
import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.core.RpcResult;
import cn.managame.rpc.core.RpcConnectionConflictException;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcLimits;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;
import cn.managame.rpc.protocol.RpcRouteMessage;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;
import cn.managame.network.netty.transport.*;
import cn.managame.rpc.core.*;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.util.HashedWheelTimer;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class RpcTcpTest {

    static ByteBuf body(String text) {
        return raw(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static ByteBuf raw(byte[] bytes) {
        return Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(bytes));
    }

    static RpcResult snapshot(RpcResult result) {
        if (!result.isSuccess()) return result;
        var r = result.value();
        return RpcResult.success(
                new RpcResponse(
                        r.requestId(),
                        r.errorCode(),
                        r.metadata(),
                        r.body() == null ? null : raw(ByteBufUtil.getBytes(r.body()))));
    }

    static RpcMessage snapshot(RpcMessage message) {
        if (message instanceof RpcRequest q)
            return new RpcRequest(
                    q.command(),
                    q.requestId(),
                    q.routeKey(),
                    q.businessId(),
                    q.businessIdType(),
                    q.metadata(),
                    raw(ByteBufUtil.getBytes(q.body())));
        if (message instanceof RpcRouteMessage r)
            return new RpcRouteMessage(
                    r.sourceNodeId(), r.targetNodeId(), raw(ByteBufUtil.getBytes(r.inner())));
        return message;
    }

    final List<RpcNode> nodes = new ArrayList<>();
    final List<RpcDiagnostic> diagnostics = new CopyOnWriteArrayList<>();

    static TestSignal<cn.managame.network.Connection> connectResult(
            RpcNode node, int target, String host, int port) {
        return connectResult(node, target, host, port, 1);
    }

    static TestSignal<cn.managame.network.Connection> connectResult(
            RpcNode node, int target, String host, int port, int count) {
        var result = new TestSignal<cn.managame.network.Connection>();
        node.connect(
                target,
                host,
                port,
                count,
                new cn.managame.network.ConnectCallback() {
                    public void onSuccess(cn.managame.network.Connection connection) {
                        result.complete(connection);
                    }

                    public void onFailure(Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    final Map<Integer, RpcCodec> envelopeCodecs = new HashMap<>();

    record Received(int nodeId, cn.managame.network.Connection connection, RpcMessage message) {}

    final Map<RpcMessage, Received> receivedMessages =
            Collections.synchronizedMap(new IdentityHashMap<>());

    RpcHandler recording(int nodeId, RpcHandler handler) {
        return (connection, msg) -> {
            var message = snapshot((RpcMessage) msg);
            receivedMessages.put(message, new Received(nodeId, connection, message));
            handler.handleUserMsg(connection, message);
        };
    }

    RpcNode receiver(RpcMessage message) {
        int nodeId = receivedMessages.get(message).nodeId();
        return nodes.stream().filter(n -> n.nodeId() == nodeId).findFirst().orElseThrow();
    }

    boolean reply(RpcMessage request, ByteBuf body) {
        return respond(request, body, 0);
    }

    boolean replyError(RpcMessage request, RpcError error) {
        return respond(request, null, error.code());
    }

    boolean respond(RpcMessage request, ByteBuf body, int error) {
        var received = receivedMessages.get(request);
        return receiver(request)
                .reply(
                        received.connection(),
                        new RpcResponse(
                                ((RpcRequest) request).requestId(),
                                error,
                                RpcMetadata.EMPTY,
                                body));
    }

    RpcNode.Builder builder(int id, RpcHandler handler) {
        return RpcNode.builder()
                .nodeId(id)
                .listen("127.0.0.1", 0)
                .handler(recording(id, handler))
                .defaultTimeout(Duration.ofSeconds(3))
                .reconnectDelay(Duration.ofMillis(50))
                .handshakeTimeout(Duration.ofMillis(500))
                .diagnostics(diagnostics::add);
    }

    RpcNode node(int id, RpcHandler handler) {
        var node = builder(id, handler).build();
        nodes.add(node);
        return node;
    }

    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(
                condition.getAsBoolean(), () -> diagnostics.stream().limit(20).toList().toString());
    }

    void connect(RpcNode a, RpcNode b, int count) throws Exception {
        b.start();
        a.start();
        a.connect(b.nodeId(), "127.0.0.1", b.localAddress().getPort(), count);
        await(
                () ->
                        a.peer(b.nodeId()).isReady()
                                && b.peer(a.nodeId()) != null
                                && b.peer(a.nodeId()).isReady());
    }

    TestSignal<RpcResult> call(RpcNode node, int target) {
        var result = new TestSignal<RpcResult>();
        node.call(
                target,
                1,
                body("hello"),
                RpcOptions.DEFAULT,
                resultValue -> result.complete(snapshot(resultValue)));
        return result;
    }

    @AfterEach
    void cleanup() {
        for (var node : nodes.reversed()) node.close();
    }

    @Test
    void businessErrorArgumentsSurviveTcpAndCallbackReturn() throws Exception {
        var error = new RpcError(10001, "金币不足");
        var result = new TestSignal<RpcResult>();
        var a = node(10, (c, m) -> {});
        var b =
                node(
                        20,
                        (c, m) -> {
                            var q = (RpcRequest) m;
                            receiver(q)
                                    .reply(
                                            c,
                                            RpcResponse.error(
                                                    q.requestId(), error, "1000", "", "金币😀"));
                        });
        connect(a, b, 1);
        a.call(20, 1, body("request"), RpcOptions.DEFAULT, result::complete);
        var received = result.get(3, TimeUnit.SECONDS);
        assertFalse(received.isSuccess());
        assertEquals(10001, received.errorCode());
        assertEquals(error, received.error());
        assertEquals("", received.error().message());
        assertNull(received.value().body());
        assertArrayEquals(new String[] {"1000", "", "金币😀"}, received.value().errorArgs());
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test
    void defaultNodeWorksOverTcpWithoutAnyCodecConfiguration() throws Exception {

        var received = new TestSignal<RpcRequest>();
        var a =
                RpcNode.builder()
                        .nodeId(10)
                        .listen("127.0.0.1", 0)
                        .handler(recording(10, (c, m) -> received.complete((RpcRequest) m)))
                        .defaultTimeout(Duration.ofSeconds(3))
                        .build();
        nodes.add(a);
        var b =
                RpcNode.builder()
                        .nodeId(20)
                        .listen("127.0.0.1", 0)
                        .handler(
                                recording(
                                        20,
                                        (c, m) -> {
                                            var request = (RpcRequest) m;
                                            assertEquals(
                                                    10,
                                                    receiver(request)
                                                            .peer(
                                                                    receivedMessages
                                                                            .get(request)
                                                                            .connection())
                                                            .nodeId());
                                            assertEquals(20, receiver(request).nodeId());
                                            reply(request, request.body());
                                        }))
                        .defaultTimeout(Duration.ofSeconds(3))
                        .build();
        nodes.add(b);
        connect(a, b, 1);
        var body = Unpooled.buffer().writeByte(9).writeInt(123);
        body.readerIndex(1);
        try {
            var result = new TestSignal<RpcResult>();
            a.call(
                    20,
                    1,
                    body,
                    RpcOptions.DEFAULT,
                    resultValue -> result.complete(snapshot(resultValue)));
            assertArrayEquals(
                    new byte[] {0, 0, 0, 123},
                    ByteBufUtil.getBytes(result.get(3, TimeUnit.SECONDS).value().body()));
            assertEquals(1, body.readerIndex());
            assertEquals(1, body.refCnt());
        } finally {
            body.release();
        }
    }

    @Test
    void defaultNodeSupportsBinaryNotifyWithoutCodecConfiguration() throws Exception {

        var receivedBuffer = new TestSignal<RpcRequest>();
        var receivedArray = new TestSignal<RpcRequest>();
        var a =
                RpcNode.builder()
                        .nodeId(10)
                        .listen("127.0.0.1", 0)
                        .handler(recording(10, (c, m) -> receivedBuffer.complete((RpcRequest) m)))
                        .defaultTimeout(Duration.ofSeconds(3))
                        .build();
        var b =
                RpcNode.builder()
                        .nodeId(20)
                        .listen("127.0.0.1", 0)
                        .handler(recording(20, (c, m) -> receivedArray.complete((RpcRequest) m)))
                        .defaultTimeout(Duration.ofSeconds(3))
                        .build();
        nodes.add(a);
        nodes.add(b);
        connect(a, b, 1);
        var body = Unpooled.buffer().writeByte(9).writeInt(123);
        body.readerIndex(1);
        try {
            assertTrue(b.send(10, 1, body, RpcOptions.builder().putLong((short) 1, 42).build()));
            var request = receivedBuffer.get(3, TimeUnit.SECONDS);
            assertEquals(0, request.requestId());
            assertEquals(
                    20,
                    receiver(request).peer(receivedMessages.get(request).connection()).nodeId());
            assertEquals(42, request.metadata().getLong((short) 1));
            assertEquals(123, ((ByteBuf) request.body()).getInt(0));
            assertEquals(1, body.readerIndex());
            assertEquals(1, body.refCnt());
            assertTrue(a.send(20, 1, raw(new byte[] {1, 2}), RpcOptions.DEFAULT));
            assertArrayEquals(
                    new byte[] {1, 2},
                    ByteBufUtil.getBytes(receivedArray.get(3, TimeUnit.SECONDS).body()));
        } finally {
            body.release();
        }
    }

    @Test
    void defaultProviderSynchronousStartAndBidirectionalCall() throws Exception {
        var a = node(-10, (c, m) -> reply((RpcMessage) m, body("from a")));
        var b = node(20, (c, m) -> reply((RpcMessage) m, body("from b")));
        connect(a, b, 2);
        assertEquals(
                "from b",
                call(a, 20)
                        .get(3, TimeUnit.SECONDS)
                        .value()
                        .body()
                        .toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(
                "from a",
                call(b, -10)
                        .get(3, TimeUnit.SECONDS)
                        .value()
                        .body()
                        .toString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(a.isRunning());
        assertNotNull(a.localAddress());
        assertEquals(2, b.peer(-10).connectionCount());
    }

    @Test
    void fullMessageAndExplicitReplyOrderingOnSameConnection() throws Exception {
        var order = new CopyOnWriteArrayList<String>();
        var stored = new AtomicReference<RpcRequest>();
        var bRef = new AtomicReference<RpcNode>();
        var b =
                node(
                        20,
                        (c, m) -> {
                            var q = (RpcRequest) m;
                            stored.set(q);
                            bRef.get().send(10, 2, body("before"), RpcOptions.DEFAULT);
                            reply(q, body("reply"));
                            bRef.get().send(10, 2, body("after"), RpcOptions.DEFAULT);
                        });
        bRef.set(b);
        var a =
                node(
                        10,
                        (c, m) ->
                                order.add(
                                        ((RpcRequest) m)
                                                .body()
                                                .toString(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        connect(a, b, 1);
        a.call(
                20,
                1,
                body("body"),
                RpcOptions.builder().routeKey(-123).busType((byte) 1).busId(-99).build(),
                r -> {
                    assertTrue(r.isSuccess());
                    order.add(r.value().body().toString(java.nio.charset.StandardCharsets.UTF_8));
                });
        await(() -> order.size() == 3);
        assertEquals(List.of("before", "reply", "after"), order);
        var q = stored.get();
        assertEquals(1, q.command());
        assertEquals(-123, q.routeKey());
        assertEquals(-99, q.businessId());
        assertEquals(10, receiver(q).peer(receivedMessages.get(q).connection()).nodeId());
        assertEquals(body("body"), q.body());
        assertTrue(reply(q, body("duplicate")));
    }

    @Test
    void delayedResponseSurvivesReconnectWithNewStringConnectionId() throws Exception {
        var request = new AtomicReference<RpcRequest>();
        var b = node(20, (c, m) -> request.set((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var peer = a.peer(20);
        var old = peer.connection(0);
        var result = call(a, 20);
        await(() -> request.get() != null);
        old.close();
        await(() -> peer.isReady() && peer.connection(0) != old && b.peer(10).isReady());
        assertSame(peer, a.peer(20));
        assertNotEquals(old.id(), peer.connection(0).id());
        assertEquals(1, peer.pendingCount());
        assertTrue(reply(request.get(), body("later")));
        assertEquals(
                "later",
                result.get(3, TimeUnit.SECONDS)
                        .value()
                        .body()
                        .toString(java.nio.charset.StandardCharsets.UTF_8));
        NettyAccess.channel(old).pipeline().fireChannelInactive();
        assertNotNull(peer.connection(0));
    }

    @Test
    void oppositeConnectionIntentFailsBothNodesAndStopsRetries() throws Exception {
        var release = new CountDownLatch(1);
        var timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        try {
            timer.newTimeout(
                    ignored -> {
                        release.await();
                    },
                    0,
                    TimeUnit.MILLISECONDS);
            var a =
                    builder(-10, (c, m) -> fail("No business during rejected handshake"))
                            .timer(timer)
                            .build();
            var b =
                    builder(20, (c, m) -> fail("No business during rejected handshake"))
                            .timer(timer)
                            .build();
            nodes.add(a);
            nodes.add(b);
            try {
                a.start();
                b.start();
                var first = connectResult(a, 20, "127.0.0.1", b.localAddress().getPort(), 2);
                var second = connectResult(b, -10, "127.0.0.1", a.localAddress().getPort(), 2);
                release.countDown();
                assertInstanceOf(
                        RpcConnectionConflictException.class,
                        assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS))
                                .getCause());
                assertInstanceOf(
                        RpcConnectionConflictException.class,
                        assertThrows(
                                        ExecutionException.class,
                                        () -> second.get(3, TimeUnit.SECONDS))
                                .getCause());
                assertFalse(a.peer(20).isReady());
                assertFalse(b.peer(-10).isReady());
                assertEquals(1L, a.eventCounts().get("connection-direction-conflict"));
                assertEquals(1L, b.eventCounts().get("connection-direction-conflict"));
                Thread.sleep(200);
                assertThrows(
                        RpcConnectionConflictException.class,
                        () -> a.connect(20, "127.0.0.1", b.localAddress().getPort(), 2));
                assertThrows(
                        RpcConnectionConflictException.class,
                        () -> b.connect(-10, "127.0.0.1", a.localAddress().getPort(), 2));
            } finally {
                release.countDown();
                a.close();
                b.close();
            }
        } finally {
            timer.stop();
        }
    }

    @Test
    void reverseConnectOnAcceptedPeerFailsSynchronouslyAndKeepsBidirectionalRpc() throws Exception {
        var a = node(10, (c, m) -> reply((RpcMessage) m, body("a")));
        var b = node(20, (c, m) -> reply((RpcMessage) m, body("b")));
        connect(a, b, 2);
        assertSame(
                a.peer(20).connection(0),
                connectResult(a, 20, "127.0.0.1", b.localAddress().getPort(), 2)
                        .get(3, TimeUnit.SECONDS));
        assertThrows(
                RpcConnectionConflictException.class,
                () -> b.connect(10, "127.0.0.1", a.localAddress().getPort(), 2));
        assertEquals(
                "b",
                call(a, 20)
                        .get(3, TimeUnit.SECONDS)
                        .value()
                        .body()
                        .toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(
                "a",
                call(b, 10)
                        .get(3, TimeUnit.SECONDS)
                        .value()
                        .body()
                        .toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void loopbackUsesRealTcpAndSameHandler() throws Exception {
        var connection = new AtomicReference<Connection>();
        var a =
                node(
                        10,
                        (c, m) -> {
                            connection.set(c);
                            reply((RpcMessage) m, body("self"));
                        });
        a.start();
        a.connect(10, "127.0.0.1", a.localAddress().getPort());
        await(() -> a.peer(10).isReady());
        assertEquals(
                "self",
                call(a, 10)
                        .get(3, TimeUnit.SECONDS)
                        .value()
                        .body()
                        .toString(java.nio.charset.StandardCharsets.UTF_8));
        assertNotSame(connection.get(), a.peer(10).connection(0));
        assertNotEquals(connection.get().id(), a.peer(10).connection(0).id());
    }

    @Test
    void unsolicitedAndDuplicateSelfHandshakesAreRejectedOverTcp() throws Exception {
        var node = node(10, (c, m) -> reply((RpcMessage) m, body("self")));
        node.start();
        int port = node.localAddress().getPort();
        for (int phase = 0; phase < 2; phase++) {
            for (int i = 0; i < 8; i++) {
                try (var socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(3000);
                    socket.getOutputStream().write(frame(handshake(4, 10, 10, 0, 1)));
                    assertEquals(-1, socket.getInputStream().read());
                }
            }
            if (phase == 0) {
                assertNull(node.peer(10));
                connectResult(node, 10, "127.0.0.1", port).get(3, TimeUnit.SECONDS);
            }
        }
        assertTrue(node.peer(10).isReady());
        assertTrue(call(node, 10).get(3, TimeUnit.SECONDS).isSuccess());
    }

    static byte[] handshake(int type, int source, int target, int slot, int count) {
        return ByteBuffer.allocate(17)
                .put((byte) type)
                .putInt(source)
                .putInt(target)
                .putInt(slot)
                .putInt(count)
                .array();
    }

    static byte[] frame(byte[] rpc) {
        return ByteBuffer.allocate(4 + rpc.length).putInt(rpc.length).put(rpc).array();
    }

    static byte[] readFrame(Socket socket) throws Exception {
        var in = new DataInputStream(socket.getInputStream());
        int size = in.readInt();
        assertTrue(size > 0 && size <= 4096);
        byte[] bytes = in.readNBytes(size);
        assertEquals(size, bytes.length);
        return bytes;
    }

    static Socket socket(RpcNode node) throws Exception {
        var s = new Socket("127.0.0.1", node.localAddress().getPort());
        s.setSoTimeout(3000);
        return s;
    }

    static byte[] request(int command, int id, String value) {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(30 + body.length)
                .put((byte) 1)
                .putInt(command)
                .putInt(id)
                .putLong(0)
                .putLong(0)
                .put((byte) 0)
                .putInt(0)
                .put(body)
                .array();
    }

    @Test
    void handshakeFragmentationAndCoalescedBusinessFrames() throws Exception {
        var delivered = new CopyOnWriteArrayList<String>();
        var a =
                node(
                        10,
                        (c, m) ->
                                delivered.add(
                                        ((RpcRequest) m)
                                                .body()
                                                .toString(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        a.start();
        try (var socket = socket(a)) {
            byte[] hello = frame(handshake(4, 20, 10, 0, 1));
            socket.getOutputStream().write(hello, 0, 2);
            socket.getOutputStream().flush();
            socket.getOutputStream().write(hello, 2, hello.length - 2);
            assertArrayEquals(handshake(5, 10, 20, 0, 1), readFrame(socket));
            var out = socket.getOutputStream();
            out.write(frame(request(2, 0, "one")));
            out.write(frame(request(2, 0, "two")));
            out.flush();
            await(() -> delivered.size() == 2);
            assertEquals(List.of("one", "two"), delivered);
        }
    }

    @Test
    void ackAndMessageInOneWriteUseReadyState() throws Exception {
        var received = new TestSignal<RpcRequest>();
        var a = node(10, (c, m) -> received.complete((RpcRequest) m));
        a.start();
        try (var server = new ServerSocket(0);
                var executor = Executors.newSingleThreadExecutor()) {
            var remote =
                    executor.submit(
                            () -> {
                                try (var s = server.accept()) {
                                    s.setSoTimeout(3000);
                                    assertArrayEquals(handshake(4, 10, 20, 0, 1), readFrame(s));
                                    var out = s.getOutputStream();
                                    var bytes = new ByteArrayOutputStream();
                                    bytes.write(frame(handshake(5, 20, 10, 0, 1)));
                                    bytes.write(frame(request(2, 0, "together")));
                                    out.write(bytes.toByteArray());
                                    out.flush();
                                    return received.get(3, TimeUnit.SECONDS);
                                }
                            });
            a.connect(20, "127.0.0.1", server.getLocalPort());
            assertEquals(body("together"), remote.get(4, TimeUnit.SECONDS).body());
        }
    }

    @Test
    void earlyBusinessBadHandshakeAndTimeoutCloseConnection() throws Exception {
        var a =
                builder(10, (c, m) -> fail("Invalid connection delivered business"))
                        .handshakeTimeout(Duration.ofMillis(80))
                        .build();
        nodes.add(a);
        a.start();
        for (byte[] bytes :
                List.of(
                        request(2, 0, "early"),
                        handshake(4, 20, 99, 0, 1),
                        handshake(4, 20, 10, 1, 1),
                        handshake(5, 20, 10, 0, 1))) {
            try (var socket = socket(a)) {
                socket.getOutputStream().write(frame(bytes));
                assertEquals(-1, socket.getInputStream().read());
            }
        }
        try (var socket = socket(a)) {
            assertEquals(-1, socket.getInputStream().read());
        }
        try (var socket = socket(a)) {
            socket.getOutputStream().write(frame(handshake(4, 20, 10, 0, 1)));
            readFrame(socket);
            socket.getOutputStream().write(frame(handshake(4, 20, 10, 0, 1)));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @TestFactory
    Stream<DynamicTest> invalidTcpPrefixes() throws Exception {
        String json = Files.readString(Path.of("../docs/OGBS Game RPC v1 wire vectors.json"));
        String section = json.substring(json.indexOf("\"invalidTcp\""));
        var m =
                Pattern.compile(
                                "\"name\"\\s*:\\s*\"([^\"]+)\".*?\"tcpHex\"\\s*:\\s*\"([0-9a-f]+)\"",
                                Pattern.DOTALL)
                        .matcher(section);
        var tests = new ArrayList<DynamicTest>();
        while (m.find()) {
            String name = m.group(1);
            byte[] bytes = HexFormat.of().parseHex(m.group(2));
            tests.add(
                    DynamicTest.dynamicTest(
                            name,
                            () -> {
                                var a = node(10, (c, message) -> fail("Invalid frame delivered"));
                                a.start();
                                try (var s = socket(a)) {
                                    s.getOutputStream().write(bytes);
                                    assertEquals(-1, s.getInputStream().read());
                                }
                                a.close();
                            }));
        }
        assertEquals(3, tests.size());
        return tests.stream();
    }

    @Test
    void nativeWriteFailureReleasesFrameAndNeverReplaysCall() throws Exception {
        var delivered = new AtomicInteger();
        var b = node(20, (c, m) -> delivered.incrementAndGet());
        var a = builder(10, (c, m) -> {}).limits(new RpcLimits(4096, 1024, 4000, 100)).build();
        nodes.add(a);
        connect(a, b, 1);
        var connection = a.peer(20).connection(0);
        var channel = NettyAccess.channel(connection);
        var held = new AtomicReference<ByteBuf>();
        var promise = new AtomicReference<ChannelPromise>();
        channel.eventLoop()
                .submit(
                        () ->
                                channel.pipeline()
                                        .addLast(
                                                "test.hold",
                                                new ChannelOutboundHandlerAdapter() {
                                                    @Override
                                                    public void write(
                                                            ChannelHandlerContext ctx,
                                                            Object message,
                                                            ChannelPromise result) {
                                                        held.set((ByteBuf) message);
                                                        promise.set(result);
                                                    }
                                                }))
                .sync();
        var result = new TestSignal<RpcResult>();
        a.call(
                20,
                1,
                body("x".repeat(40)),
                RpcOptions.builder().timeout(Duration.ofMillis(300)).build(),
                resultValue -> result.complete(snapshot(resultValue)));
        await(() -> held.get() != null);
        channel.eventLoop()
                .submit(
                        () -> {
                            held.get().release();
                            promise.get()
                                    .setFailure(new IOException("Injected native write failure"));
                        })
                .sync();
        await(() -> !connection.isActive());
        assertEquals(0, held.get().refCnt());
        assertEquals(RpcError.TIMEOUT, result.get(2, TimeUnit.SECONDS).error());
        assertEquals(0, delivered.get());
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test
    void opaqueNonUtf8BodyAndResponseMetadataSurviveTcp() throws Exception {
        var a = node(10, (c, m) -> {});
        var owner = new AtomicReference<RpcNode>();
        var b =
                node(
                        20,
                        (c, m) -> {
                            var q = (RpcRequest) m;
                            assertEquals(9876, q.command());
                            assertArrayEquals(
                                    new byte[] {(byte) 0xff, 0, (byte) 0x80},
                                    ByteBufUtil.getBytes(q.body()));
                            owner.get()
                                    .reply(
                                            c,
                                            new RpcResponse(
                                                    q.requestId(),
                                                    0,
                                                    new RpcMetadata().putInt((short) 1234, 42),
                                                    q.body()));
                        });
        owner.set(b);
        connect(a, b, 1);
        var result = new TestSignal<RpcResult>();
        a.call(
                20,
                9876,
                raw(new byte[] {(byte) 0xff, 0, (byte) 0x80}),
                RpcOptions.DEFAULT,
                resultValue -> result.complete(snapshot(resultValue)));
        var response = result.get(3, TimeUnit.SECONDS).value();
        assertTrue(response.requestId() > 0);
        assertEquals(42, response.metadata().getInt((short) 1234));
        assertArrayEquals(
                new byte[] {(byte) 0xff, 0, (byte) 0x80}, ByteBufUtil.getBytes(response.body()));
    }

    @Test
    void lifecycleRejectsBlockingOnItsOwnEventLoopWithoutClosingNode() throws Exception {
        var a = node(10, (c, m) -> {});
        var b = node(20, (c, m) -> reply((RpcMessage) m, body("ok")));
        connect(a, b, 1);
        NettyAccess.channel(a.peer(20).connection(0))
                .eventLoop()
                .submit(() -> assertThrows(IllegalStateException.class, a::close))
                .sync();
        assertTrue(a.isRunning());
        assertTrue(call(a, 20).get(3, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void localBindFailureRollsBackAndSharedResourcesSurviveClose() throws Exception {
        try (var occupied = new ServerSocket(0)) {
            var failed =
                    builder(10, (c, m) -> {}).listen("127.0.0.1", occupied.getLocalPort()).build();
            nodes.add(failed);
            assertThrows(RuntimeException.class, failed::start);
            assertTrue(failed.isClosed());
        }
        var shared = NetworkResources.builder().ioThreads(1).build();
        try {
            var a = builder(10, (c, m) -> {}).provider(new NettyRpcNetworkProvider(shared)).build();
            nodes.add(a);
            a.start();
            a.close();
            assertFalse(shared.isClosed());
            var b = builder(20, (c, m) -> {}).provider(new NettyRpcNetworkProvider(shared)).build();
            nodes.add(b);
            b.start();
            b.close();
            assertFalse(shared.isClosed());
        } finally {
            shared.close();
        }
    }

    @Test
    void forwardingHappensOnlyWhenTheUserHandlerExplicitlySends() throws Exception {
        var targetMessage = new TestSignal<RpcRouteMessage>();
        var relay = new AtomicReference<RpcNode>();
        var a = builder(10, (c, m) -> {}).build();
        nodes.add(a);
        var b =
                builder(
                                30,
                                (c, m) ->
                                        assertTrue(
                                                relay.get()
                                                        .send(
                                                                20,
                                                                (RpcRouteMessage) m,
                                                                RpcOptions.DEFAULT)))
                        .build();
        nodes.add(b);
        relay.set(b);
        var c =
                builder(
                                20,
                                (conn, msg) ->
                                        targetMessage.complete(
                                                (RpcRouteMessage) snapshot((RpcMessage) msg)))
                        .build();
        nodes.add(c);
        connect(a, b, 1);
        connect(b, c, 1);
        assertTrue(
                a.send(
                        30,
                        new RpcRouteMessage(10, 20, body("opaque payload")),
                        RpcOptions.DEFAULT));
        var received = targetMessage.get(3, TimeUnit.SECONDS);
        assertEquals(10, received.sourceNodeId());
        assertEquals(20, received.targetNodeId());
        assertEquals(body("opaque payload"), received.inner());
        assertNull(a.peer(20));
        assertNull(c.peer(10));
    }
}
