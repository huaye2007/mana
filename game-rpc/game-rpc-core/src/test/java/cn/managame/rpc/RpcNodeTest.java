package cn.managame.rpc;

import static cn.managame.rpc.RpcMetadataUtil.*;
import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.ConnectCallback;
import cn.managame.network.Connection;

import io.netty.buffer.ByteBuf;
import io.netty.util.HashedWheelTimer;

import org.junit.jupiter.api.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcNodeTest {
    final Network network = new Network();
    final List<RpcNode> nodes = new ArrayList<>();
    final List<RpcDiagnostic> diagnostics = new CopyOnWriteArrayList<>();

    record Received(int nodeId, cn.managame.network.Connection connection, RpcMessage message) {}

    final Map<RpcMessage, Received> receivedMessages =
            Collections.synchronizedMap(new IdentityHashMap<>());

    RpcHandler recording(int nodeId, RpcHandler handler) {
        return (connection, msg) -> {
            var message = (RpcMessage) msg;
            receivedMessages.put(message, new Received(nodeId, connection, message));
            handler.handleUserMsg(connection, msg);
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
                .defaultTimeout(Duration.ofSeconds(2))
                .reconnectDelay(Duration.ofMillis(10))
                .handshakeTimeout(Duration.ofMillis(200))
                .limits(LIMITS)
                .diagnostics(diagnostics::add)
                .provider(network);
    }

    RpcNode node(int id, RpcHandler handler) {
        var node = builder(id, handler).build();
        nodes.add(node);
        return node;
    }

    void connect(RpcNode a, RpcNode b, int count) throws Exception {
        b.start();
        a.start();
        a.connect(b.nodeId(), "127.0.0.1", b.localAddress().getPort(), count);
        try {
            await(
                    () ->
                            a.peer(b.nodeId()).isReady()
                                    && b.peer(a.nodeId()) != null
                                    && b.peer(a.nodeId()).isReady());
        } catch (AssertionError error) {
            var p = a.peer(b.nodeId());
            throw new AssertionError(
                    "running="
                            + a.isRunning()
                            + ", closed="
                            + p.isClosed()
                            + ", ready="
                            + p.isReady()
                            + ", events="
                            + diagnostics.stream().limit(8).toList()
                            + ", sent="
                            + network.transports.get(a.nodeId()).sent.size()
                            + ", threads="
                            + Thread.getAllStackTraces().entrySet().stream()
                                    .filter(e -> e.getKey().getName().startsWith("rpc-"))
                                    .map(e -> e.getKey().getName() + Arrays.toString(e.getValue()))
                                    .toList(),
                    error);
        }
    }

    CompletableFuture<RpcResult> call(RpcNode a, int target, RpcOptions options) {
        var result = new CompletableFuture<RpcResult>();
        a.call(
                target,
                1,
                body("hello"),
                options,
                resultValue -> result.complete(snapshot(resultValue)));
        return result;
    }

    @AfterEach
    void close() {
        for (var node : nodes.reversed()) node.close();
    }

    @Test
    void arbitraryCommandsAreDeliveredWithoutRegistration() throws Exception {
        var received = new CompletableFuture<RpcRequest>();
        var a = node(10, (c, m) -> {});
        var b =
                node(
                        20,
                        (c, m) -> {
                            var request = (RpcRequest) m;
                            if (request.requestId() > 0) {
                                assertEquals(Integer.MAX_VALUE, request.command());
                                reply(request, body("reply"));
                            } else received.complete(request);
                        });
        connect(a, b, 1);
        var result = new CompletableFuture<RpcResult>();
        a.call(
                20,
                Integer.MAX_VALUE,
                body("raw"),
                RpcOptions.DEFAULT,
                resultValue -> result.complete(snapshot(resultValue)));
        assertEquals(body("reply"), result.get(3, TimeUnit.SECONDS).value().body());
        assertTrue(a.send(20, Integer.MAX_VALUE, body("notice"), RpcOptions.DEFAULT));
        var notification = received.get(3, TimeUnit.SECONDS);
        assertEquals(Integer.MAX_VALUE, notification.command());
        assertEquals(body("notice"), notification.body());
        assertEquals(0, notification.requestId());
        assertFalse(reply(notification, body("invalid")));
    }

    @Test
    void synchronousStartAndFailureRollback() {
        var a = node(10, (c, m) -> {});
        a.start();
        assertTrue(a.isRunning());
        assertTrue(a.localAddress().getPort() > 0);
        int port = a.localAddress().getPort();
        a.start();
        assertEquals(port, a.localAddress().getPort());
        var fail = node(30, (c, m) -> {});
        network.transports.get(30).failStart = true;
        assertThrows(IllegalStateException.class, fail::start);
        assertTrue(fail.isClosed());
        assertTrue(network.transports.get(30).closed);
    }

    @Test
    void beforeAndAfterStartAdmissionAndBorrowedTimer() throws Exception {
        var timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        try {
            var a = builder(10, (c, m) -> {}).timer(timer).build();
            nodes.add(a);
            assertEquals(RpcError.UNAVAILABLE, call(a, 20, RpcOptions.DEFAULT).get().error());
            a.start();
            a.close();
            var stillRunning = new CompletableFuture<Boolean>();
            timer.newTimeout(ignored -> stillRunning.complete(true), 0, TimeUnit.MILLISECONDS);
            assertTrue(stillRunning.get(2, TimeUnit.SECONDS));
            assertEquals(RpcError.UNAVAILABLE, call(a, 20, RpcOptions.DEFAULT).get().error());
            assertThrows(IllegalStateException.class, a::start);
            var owned = node(30, (c, m) -> {});
            assertInstanceOf(HashedWheelTimer.class, owned.timer);
            owned.start();
            owned.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> owned.timer.newTimeout(ignored -> {}, 1, TimeUnit.SECONDS));
        } finally {
            timer.stop();
        }
    }

    @Test
    void fullMessageExplicitReplyOrderingAndUnknownMetadata() throws Exception {
        var received = new AtomicReference<RpcRequest>();
        var events = new CopyOnWriteArrayList<String>();
        var bRef = new AtomicReference<RpcNode>();
        var b =
                node(
                        20,
                        (c, m) -> {
                            assertInstanceOf(RpcMessage.class, m);
                            var q = (RpcRequest) m;
                            received.set(q);
                            assertEquals(
                                    10,
                                    receiver(q)
                                            .peer(receivedMessages.get(q).connection())
                                            .nodeId());
                            assertEquals(20, receiver(q).nodeId());
                            bRef.get().send(10, 2, body("before"), RpcOptions.DEFAULT);
                            assertTrue(reply(q, body("accepted")));
                            assertTrue(reply(q, body("duplicate")));
                            bRef.get().send(10, 2, body("after"), RpcOptions.DEFAULT);
                        });
        bRef.set(b);
        var a =
                node(
                        10,
                        (c, m) ->
                                events.add(
                                        ((RpcRequest) m)
                                                .body()
                                                .toString(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        connect(a, b, 1);
        var options =
                RpcOptions.builder()
                        .routeKey(-7)
                        .busType((byte) 1)
                        .busId(-88)
                        .putLong((short) 1024, -99)
                        .build();
        a.call(
                20,
                1,
                body("hello"),
                options,
                result -> {
                    assertTrue(result.isSuccess());
                    events.add("response");
                });
        assertEquals(List.of("before", "response", "after"), events);
        var q = received.get();
        assertEquals(1, q.command());
        assertEquals(-7, q.routeKey());
        assertEquals(-88, q.businessId());
        assertEquals(-99, getLong(q.metadata(), (short) 1024));
        assertEquals(11, q.metadataLength());
        assertEquals(body("hello"), q.body());
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test
    void completeResponsesMatchRequestsAndReplyDoesNotSpreadBackpressure() throws Exception {
        var requests = new ArrayList<RpcRequest>();
        var b = node(20, (c, m) -> requests.add((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 2);
        var first = call(a, 20, RpcOptions.route(123));
        var second = call(a, 20, RpcOptions.route(123));
        var q1 = requests.get(0);
        var q2 = requests.get(1);
        var incoming = (Network.Conn) receivedMessages.get(q1).connection();
        assertSame(incoming, receivedMessages.get(q2).connection());
        var response = new RpcResponse(q2.requestId(), 0, RpcMetadata.EMPTY, body("second"));
        incoming.writable = false;
        assertFalse(b.reply(incoming, response));
        assertFalse(second.isDone());
        incoming.writable = true;
        assertThrows(IllegalArgumentException.class, () -> b.reply(incoming, q1));
        assertTrue(b.reply(incoming, response));
        assertEquals(
                "second",
                second.get().value().body().toString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(first.isDone());
        assertTrue(b.reply(incoming, response)); // No receiver-side per-request state.
        assertTrue(
                b.reply(
                        incoming,
                        new RpcResponse(q1.requestId(), 0, RpcMetadata.EMPTY, body("first"))));
        assertEquals(
                "first",
                first.get().value().body().toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2L, a.eventCounts().get("call-success"));
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test
    void retiredConnectionIdentityCannotReplyInARejoinedPeerLifecycle() throws Exception {
        var requests = new ArrayList<RpcRequest>();
        var b = node(20, (c, m) -> requests.add((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var originalCall = call(a, 20, RpcOptions.DEFAULT);
        var originalRequest = requests.getFirst();
        var originalConnection = receivedMessages.get(originalRequest).connection();
        var originalPeer = b.peer(originalConnection);
        assertSame(b.peer(10), originalPeer);
        assertNull(a.peer(originalConnection));
        b.removePeer(10);
        await(() -> b.peer(10) != null && b.peer(10).isReady() && a.peer(20).isReady());
        assertNotSame(originalPeer, b.peer(10));
        assertNull(b.peer(originalConnection));
        assertFalse(
                b.reply(
                        originalConnection,
                        new RpcResponse(
                                originalRequest.requestId(), 0, RpcMetadata.EMPTY, body("old"))));
        var newCall = call(a, 20, RpcOptions.DEFAULT);
        assertTrue(reply(requests.getLast(), body("new")));
        assertEquals(
                "new",
                newCall.get().value().body().toString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(originalCall.isDone());
    }

    @Test
    void delayedReplyDoesNotFollowHandlerReturn() throws Exception {
        var received = new AtomicReference<RpcRequest>();
        var b = node(20, (c, m) -> received.set((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var result = call(a, 20, RpcOptions.DEFAULT);
        assertFalse(result.isDone());
        assertNotNull(received.get());
        assertTrue(reply(received.get(), body("later")));
        assertEquals(
                "later",
                result.get().value().body().toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void arbitraryMetadataBytesReachCallAndSendWithoutTypeRegistration() throws Exception {
        var values = new CopyOnWriteArrayList<byte[]>();
        var a = node(10, (c, m) -> {});
        var b =
                node(
                        20,
                        (c, m) -> {
                            var q = (RpcRequest) m;
                            values.add(q.metadata().get((short) 1024));
                            assertTrue(getBoolean(q.metadata(), (short) 1027));
                            if (q.requestId() > 0) reply(q, body("ok"));
                        });
        connect(a, b, 1);
        var opaque = new byte[] {(byte) 0xff, 2, 3};
        var options =
                RpcOptions.builder()
                        .put((short) 1024, opaque)
                        .putBoolean((short) 1027, true)
                        .build();
        assertTrue(call(a, 20, options).get(3, TimeUnit.SECONDS).isSuccess());
        assertTrue(a.send(20, 2, body("send"), options));
        assertEquals(2, values.size());
        for (var value : values) assertArrayEquals(opaque, value);
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test
    void sendIgnoresTimeoutAndDoesNotAdvanceCounterOrReply() throws Exception {
        var received = new AtomicReference<RpcRequest>();
        var b = node(20, (c, m) -> received.set((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        assertTrue(
                a.send(
                        20,
                        2,
                        body("notice"),
                        RpcOptions.builder().timeout(Duration.ofNanos(1)).build()));
        assertEquals(0, received.get().requestId());
        assertFalse(replyError(received.get(), RpcError.NO_HANDLER));
        assertEquals(0, a.calls.requestIdCounter.get());
        assertEquals(0, a.peer(20).pendingCount());
        assertEquals(
                0, network.transports.get(20).sent.stream().filter(bytes -> bytes[0] == 2).count());
    }

    @Test
    void callTimeoutAndLateResponseOnlyCompleteOnce() throws Exception {
        var q = new AtomicReference<RpcRequest>();
        var b = node(20, (c, m) -> q.set((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var result = call(a, 20, RpcOptions.builder().timeout(Duration.ofMillis(30)).build());
        assertEquals(RpcError.TIMEOUT, result.get(2, TimeUnit.SECONDS).error());
        reply(q.get(), body("too late"));
        assertEquals(0, a.peer(20).pendingCount());
        assertEquals(1, a.eventCounts().get("call-failure"));
    }

    @Test
    void concurrentRolloverSkipsOccupiedIdsAndOldCompletionCannotRemoveReusedId() throws Exception {
        var requests = new CopyOnWriteArrayList<RpcRequest>();
        var b = node(20, (c, m) -> requests.add((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var first = call(a, 20, RpcOptions.DEFAULT);
        var peer = a.peer(20);
        var old = peer.pending(1);
        a.calls.requestIdCounter.set(Integer.MAX_VALUE - 1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++)
                jobs.add(executor.submit(() -> call(a, 20, RpcOptions.DEFAULT)));
            for (var job : jobs) job.get();
        }
        assertEquals(41, peer.pendingCount());
        assertEquals(
                41, new HashSet<>(requests.stream().map(RpcRequest::requestId).toList()).size());
        assertTrue(requests.stream().anyMatch(q -> q.requestId() == Integer.MAX_VALUE));
        reply(requests.getFirst(), body("first"));
        assertTrue(first.get().isSuccess());
        a.calls.requestIdCounter.set(0);
        var reused = call(a, 20, RpcOptions.DEFAULT);
        var replacement = peer.pending(1);
        assertNotSame(old, replacement);
        a.calls.timeoutCall(peer, old);
        assertSame(replacement, peer.pending(1));
        assertFalse(reused.isDone());
        for (var q : requests.subList(1, requests.size())) reply(q, body("complete"));
        assertEquals(0, peer.pendingCount());
    }

    @Test
    void invalidParametersThrowBeforeCallback() {
        var a = node(10, (c, m) -> {});
        var called = new AtomicInteger();
        assertThrows(
                IllegalArgumentException.class,
                () -> a.call(20, 0, body("x"), RpcOptions.DEFAULT, r -> called.incrementAndGet()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        a.call(
                                20,
                                -1,
                                body("no response"),
                                RpcOptions.DEFAULT,
                                r -> called.incrementAndGet()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RpcOptions(0, (byte) 0, 1, RpcMetadata.EMPTY, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> RpcOptions.builder().timeout(Duration.ZERO).build());
        assertEquals(0, called.get());
    }

    @Test
    void hashFailureDoesNotSpillAndRoundRobinCanUseAnotherSlot() throws Exception {
        var b = node(20, (c, m) -> {});
        var a = node(10, (c, m) -> {});
        connect(a, b, 2);
        var peer = a.peer(20);
        var dead = (Network.Conn) peer.connection(0);
        dead.writable = false;
        long key = 1;
        while (ConnectionSelector.DEFAULT.select(peer, key) != dead) key++;
        var result = call(a, 20, RpcOptions.route(key)).get();
        assertEquals(RpcError.OVERLOADED, result.error());
        assertTrue(a.send(20, 2, body("other"), RpcOptions.DEFAULT));
        assertFalse(a.send(20, 2, body("fixed"), RpcOptions.route(key)));
    }

    @Test
    void pendingLimitIsPerTargetAndReleasesAfterTimeout() throws Exception {
        var b = node(20, (c, m) -> {});
        var a = builder(10, (c, m) -> {}).limits(new RpcLimits(4096, 1024, 4000, 1)).build();
        nodes.add(a);
        connect(a, b, 1);
        var first = call(a, 20, RpcOptions.DEFAULT);
        assertEquals(RpcError.OVERLOADED, call(a, 20, RpcOptions.DEFAULT).get().error());
        a.removePeer(20);
        assertEquals(RpcError.UNAVAILABLE, first.get().error());
    }

    @Test
    void callbackAndHandlerExceptionsDoNotDoubleComplete() throws Exception {
        var b =
                node(
                        20,
                        (c, m) -> {
                            var q = (RpcRequest) m;
                            reply(q, body("ok"));
                            throw new IllegalStateException("after reply");
                        });
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var count = new AtomicInteger();
        a.call(
                20,
                1,
                body("x"),
                RpcOptions.DEFAULT,
                r -> {
                    count.incrementAndGet();
                    throw new IllegalStateException("callback");
                });
        assertEquals(1, count.get());
        assertEquals(0, a.peer(20).pendingCount());
        assertEquals(1, a.eventCounts().get("callback-failed"));
        assertEquals(
                1, network.transports.get(20).sent.stream().filter(bytes -> bytes[0] == 2).count());
    }

    @Test
    void originalConnectionCanCloseBeforeDelayedReply() throws Exception {
        var q = new AtomicReference<RpcRequest>();
        var b = node(20, (c, m) -> q.set((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var peer = a.peer(20);
        var original = peer.connection(0);
        var result = call(a, 20, RpcOptions.DEFAULT);
        original.close();
        assertEquals(1, peer.pendingCount());
        await(() -> peer.isReady() && peer.connection(0) != original && b.peer(10).isReady());
        assertSame(peer, a.peer(20));
        assertNotEquals(original.id(), peer.connection(0).id());
        ((Network.Conn) original).handler.onDisconnected(original);
        assertNotNull(peer.connection(0));
        reply(q.get(), body("after reconnect"));
        assertTrue(result.get().isSuccess());
    }

    @Test
    void removalRetiresOldPeerAndInboundHandshakeAutomaticallyCreatesNewPeer() throws Exception {
        var b = node(20, (c, m) -> {});
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var old = b.peer(10);
        var connection = old.connection(0);
        var pending = call(b, 10, RpcOptions.DEFAULT);
        b.removePeer(10);
        assertTrue(old.isClosed());
        assertEquals(RpcError.UNAVAILABLE, pending.get().error());
        await(() -> b.peer(10) != null && b.peer(10).isReady() && a.peer(20).isReady());
        var current = b.peer(10);
        assertNotSame(old, current);
        assertNotEquals(connection.id(), current.connection(0).id());
        ((Network.Conn) connection).handler.onDisconnected(connection);
        assertSame(current, b.peer(10));
        assertTrue(current.isReady());
        assertTrue(b.send(10, 2, body("rejoined"), RpcOptions.DEFAULT));
    }

    @Test
    void admissionControlsRejoinWithoutReceiverRegistration() throws Exception {
        var allowed = new AtomicBoolean(true);
        var b = builder(20, (c, m) -> {}).peerAdmission(id -> allowed.get()).build();
        nodes.add(b);
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        allowed.set(false);
        b.removePeer(10);
        await(() -> b.eventCounts().getOrDefault("handshake-rejected", 0L) > 0);
        assertNull(b.peer(10));
        allowed.set(true);
        await(() -> b.peer(10) != null && b.peer(10).isReady() && a.peer(20).isReady());
    }

    @Test
    void connectRequiresStartedNodeAndWaitsForAllHandshakeSlots() throws Exception {
        var a = node(10, (c, m) -> {});
        var b = node(20, (c, m) -> {});
        assertThrows(IllegalStateException.class, () -> a.connect(20, "127.0.0.1", 15000));
        assertNull(a.peer(20));
        a.start();
        b.start();
        var ready = connectResult(a, 20, "127.0.0.1", b.localAddress().getPort(), 2);
        var connected = ready.get(3, TimeUnit.SECONDS);
        var peer = a.peer(20);
        assertTrue(peer.contains(connected));
        assertTrue(peer.isReady());
        assertEquals(2, peer.connectionCount());
        assertSame(
                peer.connection(0),
                connectResult(a, 20, "127.0.0.1", b.localAddress().getPort(), 2).get());
        assertThrows(
                RpcConnectionConflictException.class,
                () -> b.connect(10, "127.0.0.1", a.localAddress().getPort(), 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> a.connect(20, "127.0.0.1", b.localAddress().getPort(), 3));
        var unavailable = connectResult(a, 30, "127.0.0.1", 1);
        assertFalse(unavailable.isDone());
        a.removePeer(30);
        assertThrows(ExecutionException.class, () -> unavailable.get(3, TimeUnit.SECONDS));
        var closing = connectResult(a, 40, "127.0.0.1", 1);
        a.close();
        assertThrows(ExecutionException.class, () -> closing.get(3, TimeUnit.SECONDS));
    }

    @Test
    void connectCallbacksAreIsolatedAndNewCallsWaitForReconnect() throws Exception {
        var timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        var paused = new Semaphore(0);
        var resume = new Semaphore(0);
        Runnable pauseTimer =
                () -> {
                    paused.release();
                    try {
                        assertTrue(resume.tryAcquire(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        var a = builder(10, (c, m) -> {}).timer(timer).build();
        nodes.add(a);
        var b = node(20, (c, m) -> {});
        var notifications = new AtomicInteger();
        var throwingCallback =
                new ConnectCallback() {
                    public void onSuccess(Connection connection) {
                        notifications.incrementAndGet();
                        throw new IllegalStateException("User callback failed");
                    }

                    public void onFailure(Throwable failure) {
                        notifications.incrementAndGet();
                        throw new AssertionError(failure);
                    }
                };
        try {
            b.start();
            int port = b.localAddress().getPort();
            assertThrows(
                    IllegalStateException.class,
                    () -> a.connect(20, "127.0.0.1", port, 2, throwingCallback));
            a.start();
            // Queue two callers before either slot can start connecting.
            timer.newTimeout(ignored -> pauseTimer.run(), 0, TimeUnit.MILLISECONDS);
            assertTrue(paused.tryAcquire(3, TimeUnit.SECONDS));
            a.connect(20, "127.0.0.1", port, 2, throwingCallback);
            var ready = connectResult(a, 20, "127.0.0.1", port, 2);
            assertFalse(ready.isDone());
            assertEquals(0, notifications.get());
            resume.release();
            var connection = ready.get(3, TimeUnit.SECONDS);
            var peer = a.peer(20);
            assertTrue(peer.isReady());
            assertTrue(peer.contains(connection));
            assertEquals(1, notifications.get());
            assertEquals(1L, a.eventCounts().get("connect-callback-failed"));

            // A new invocation must observe current readiness, not the previous success.
            timer.newTimeout(ignored -> pauseTimer.run(), 0, TimeUnit.MILLISECONDS);
            assertTrue(paused.tryAcquire(3, TimeUnit.SECONDS));
            var old = peer.connection(0);
            old.close();
            assertFalse(peer.isReady());
            var reconnected = connectResult(a, 20, "127.0.0.1", port, 2);
            assertFalse(reconnected.isDone());
            resume.release();
            assertTrue(peer.contains(reconnected.get(3, TimeUnit.SECONDS)));
            assertSame(peer, a.peer(20));
            assertTrue(peer.isReady());
            assertNotEquals(old.id(), peer.connection(0).id());
            assertEquals(1, notifications.get());
        } finally {
            resume.release(2);
            a.close();
            timer.stop();
        }
    }

    @Test
    void disconnectedInboundPeerCannotSwitchDirectionUntilRemoved() throws Exception {
        var a = node(10, (c, m) -> {});
        var b = node(20, (c, m) -> {});
        connect(a, b, 1);
        var inbound = b.peer(10);
        a.removePeer(20);
        assertFalse(inbound.isReady());
        assertThrows(
                RpcConnectionConflictException.class,
                () -> b.connect(10, "127.0.0.1", a.localAddress().getPort(), 2));
        assertSame(inbound, b.peer(10));
        b.removePeer(10);
        connectResult(b, 10, "127.0.0.1", a.localAddress().getPort()).get(3, TimeUnit.SECONDS);
        assertNotSame(inbound, b.peer(10));
        assertTrue(a.peer(20).isReady());
    }

    @Test
    void malformedCallAndResponseFollowErrorMatrixWithoutResponseLoops() throws Exception {
        var b = node(20, (c, m) -> {});
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        network.transports.get(10).dropBusiness = true;
        var result = call(a, 20, RpcOptions.DEFAULT);
        var frame =
                network.transports.get(10).sent.stream()
                        .filter(bytes -> bytes[0] == 1)
                        .findFirst()
                        .orElseThrow()
                        .clone();
        ByteBuffer.wrap(frame).putInt(26, -1);
        ((Network.Conn) b.peer(10).connection(0))
                .handler.onMessage(b.peer(10).connection(0), raw(frame));
        assertEquals(RpcError.PROTOCOL_ERROR, result.get().error());
        int responses = network.transports.get(20).sent.size();
        ByteBuffer.wrap(frame).putInt(5, 0);
        ((Network.Conn) b.peer(10).connection(0))
                .handler.onMessage(b.peer(10).connection(0), raw(frame));
        assertEquals(responses, network.transports.get(20).sent.size());
        var another = call(a, 20, RpcOptions.DEFAULT);
        int id = a.peer(20).pendingSnapshot().getFirst().requestId;
        var bad = ByteBuffer.allocate(13).put((byte) 2).putInt(id).putInt(-1).putInt(0);
        ((Network.Conn) a.peer(20).connection(0))
                .handler.onMessage(a.peer(20).connection(0), raw(bad.array()));
        assertEquals(RpcError.PROTOCOL_ERROR, another.get().error());
        assertEquals(responses, network.transports.get(20).sent.size());
        // Business errors preserve their code and do not leave calls pending.
        var unknown = call(a, 20, RpcOptions.DEFAULT);
        int unknownId = a.peer(20).pendingSnapshot().getFirst().requestId;
        var unknownFrame =
                new DefaultRpcCodec()
                        .encode(
                                new RpcResponse(
                                        unknownId, Integer.MAX_VALUE, RpcMetadata.EMPTY, null));
        long failures = a.eventCounts().getOrDefault("call-failure", 0L);
        try {
            ((Network.Conn) a.peer(20).connection(0))
                    .handler.onMessage(a.peer(20).connection(0), unknownFrame);
            ((Network.Conn) a.peer(20).connection(0))
                    .handler.onMessage(a.peer(20).connection(0), unknownFrame);
            assertFalse(unknown.get().isSuccess());
            assertEquals(Integer.MAX_VALUE, unknown.get().error().code());
            assertEquals(Integer.MAX_VALUE, unknown.get().value().errorCode());
            assertEquals(0, a.peer(20).pendingCount());
            assertEquals(failures + 1, a.eventCounts().get("call-failure"));
            assertEquals(responses, network.transports.get(20).sent.size());
        } finally {
            unknownFrame.release();
        }
    }

    @Test
    void userHandlerDecidesUnknownCommandError() throws Exception {
        var b =
                node(
                        20,
                        (c, m) -> {
                            var request = (RpcRequest) m;
                            assertEquals(100, request.command());
                            replyError(request, RpcError.NO_HANDLER);
                        });
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        network.transports.get(10).dropBusiness = true;
        var result = call(a, 20, RpcOptions.DEFAULT);
        var frame =
                network.transports.get(10).sent.stream()
                        .filter(bytes -> bytes[0] == 1)
                        .findFirst()
                        .orElseThrow()
                        .clone();
        ByteBuffer.wrap(frame).putInt(1, 100);
        ((Network.Conn) b.peer(10).connection(0))
                .handler.onMessage(b.peer(10).connection(0), raw(frame));
        assertEquals(RpcError.NO_HANDLER, result.get().error());
        byte[] response = network.transports.get(20).sent.getLast();
        assertEquals(13, response.length);
        assertEquals(0, response[9]);
    }

    @Test
    void competingResponsesCompleteTheCallerOnlyOnce() throws Exception {
        var stored = new AtomicReference<RpcRequest>();
        var b = node(20, (c, m) -> stored.set((RpcRequest) m));
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var result = call(a, 20, RpcOptions.DEFAULT);
        var accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) {
                int index = i;
                tasks.add(
                        executor.submit(
                                () -> {
                                    if (index % 2 == 0
                                            ? reply(stored.get(), body("ok"))
                                            : replyError(stored.get(), RpcError.INTERNAL_ERROR))
                                        accepted.incrementAndGet();
                                }));
            }
            for (var task : tasks) task.get();
        }
        assertTrue(result.isDone());
        assertEquals(20, accepted.get());
        assertEquals(0, a.peer(20).pendingCount());
        assertEquals(
                20,
                network.transports.get(20).sent.stream().filter(bytes -> bytes[0] == 2).count());
        assertEquals(
                1L,
                a.eventCounts().getOrDefault("call-success", 0L)
                        + a.eventCounts().getOrDefault("call-failure", 0L));
    }

    @Test
    void responseWireOverflowFallsBackToOneFrameworkError() throws Exception {
        var b =
                builder(20, (c, m) -> reply((RpcMessage) m, body("x".repeat(4090))))
                        .limits(new RpcLimits(4096, 1024, 4096, 100))
                        .build();
        nodes.add(b);
        var a = node(10, (c, m) -> {});
        connect(a, b, 1);
        var result = call(a, 20, RpcOptions.DEFAULT).get();
        assertEquals(RpcError.INTERNAL_ERROR, result.error());
        assertEquals(13, network.transports.get(20).sent.getLast().length);
    }

    @Test
    void codecDelayAfterDeadlineNeverSubmitsRequest() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slow =
                new DefaultRpcCodec() {
                    public ByteBuf encode(RpcMessage message) {
                        if (message instanceof RpcRequest) {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return super.encode(message);
                    }
                };
        var b = node(20, (c, m) -> fail("Expired request delivered"));
        var a = builder(10, (c, m) -> {}).codec(slow).build();
        nodes.add(a);
        connect(a, b, 1);
        var result = new CompletableFuture<RpcResult>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sent =
                    executor.submit(
                            () ->
                                    a.call(
                                            20,
                                            1,
                                            body("x"),
                                            RpcOptions.builder()
                                                    .timeout(Duration.ofMillis(40))
                                                    .build(),
                                            resultValue -> result.complete(snapshot(resultValue))));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(RpcError.TIMEOUT, result.get(1, TimeUnit.SECONDS).error());
            release.countDown();
            sent.get();
        } finally {
            release.countDown();
        }
        assertEquals(
                0, network.transports.get(10).sent.stream().filter(bytes -> bytes[0] == 1).count());
    }
}
