package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcConnectionBoundaryTest {
    final RpcNodeTest fixture = new RpcNodeTest();
    final TestCodec codec = new TestCodec(ALLOCATOR, LIMITS);

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void unsolicitedSelfHandshakesCannotCreateAPeerOrConsumeReadySlots() throws Exception {
        var node =
                fixture.builder(10, (c, m) -> {})
                        .maxPeers(1)
                        .maxConnectionsPerPeer(1)
                        .maxPendingHandshakes(1)
                        .build();
        fixture.nodes.add(node);
        node.start();
        for (int i = 0; i < 100; i++) {
            var c = incoming(node);
            c.handler.onMessage(c, codec.handshake((byte) 4, 10, 10, 0, 1));
            assertFalse(c.isActive());
            assertNull(node.peer(c));
        }
        assertNull(node.peer(10));
        // Rejections must also return the handshake admission permit.
        var remote = fixture.node(20, (c, m) -> {});
        fixture.connect(remote, node, 1);
        assertTrue(node.peer(20).isReady());
    }

    @Test
    void loopbackKeepsOnePairPerSlotAndStillReconnects() throws Exception {
        var node = fixture.node(10, (c, m) -> {});
        node.start();
        connectResult(node, 10, "127.0.0.1", node.localAddress().getPort(), 2)
                .get(3, TimeUnit.SECONDS);
        var peer = node.peer(10);
        var transport = fixture.network.transports.get(10);
        for (int i = 0; i < 100; i++) {
            var duplicate = incoming(node);
            duplicate.handler.onMessage(duplicate, codec.handshake((byte) 4, 10, 10, i % 2, 2));
            assertFalse(duplicate.isActive());
        }
        assertEquals(4, transport.connections.size());
        assertTrue(peer.isReady());
        var old = peer.connection(0);
        old.close();
        connectResult(node, 10, "127.0.0.1", node.localAddress().getPort(), 2)
                .get(3, TimeUnit.SECONDS);
        assertSame(peer, node.peer(10));
        assertNotSame(old, peer.connection(0));
        assertEquals(4, transport.connections.size());
    }

    @Test
    void offlineConnectWaitersAreBoundedAndRemovalNotifiesEveryAcceptedWaiter() throws Exception {
        var node = fixture.node(10, (c, m) -> {});
        node.start();
        var results = new ArrayList<CompletableFuture<Connection>>();
        for (int i = 0; i < 64; i++) results.add(connectResult(node, 20, "127.0.0.1", 25000));
        for (int i = 0; i < 1000; i++) {
            var error =
                    assertThrows(
                            RpcException.class, () -> connectResult(node, 20, "127.0.0.1", 25000));
            assertEquals(RpcError.OVERLOADED, error.error());
        }
        // Repeated calls without a callback retain no extra waiters.
        node.connect(20, "127.0.0.1", 25000);
        node.removePeer(20);
        for (var result : results)
            assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
    }

    @Test
    void removedOutboundPlanDoesNotConstrainNewDirectionOrSlotCount() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        var old = a.peer(20);
        a.removePeer(20);
        b.removePeer(10);
        connectResult(b, 10, "127.0.0.1", a.localAddress().getPort(), 2).get(3, TimeUnit.SECONDS);
        await(() -> a.peer(20) != null && a.peer(20).isReady());
        assertNotSame(old, a.peer(20));
        assertEquals(2, a.peer(20).connectionCount());
        assertThrows(
                RpcConnectionConflictException.class,
                () -> a.connect(20, "127.0.0.1", b.localAddress().getPort(), 2));
    }

    @Test
    void successfulConnectReleasesWaiterCapacity() throws Exception {
        var node = fixture.node(10, (c, m) -> {});
        node.start();
        var results = new ArrayList<CompletableFuture<Connection>>();
        for (int i = 0; i < 64; i++) results.add(connectResult(node, 20, "127.0.0.1", 25000));
        var remote = fixture.builder(20, (c, m) -> {}).listen("127.0.0.1", 25000).build();
        fixture.nodes.add(remote);
        remote.start();
        for (var result : results) assertNotNull(result.get(3, TimeUnit.SECONDS));
        assertNotNull(connectResult(node, 20, "127.0.0.1", 25000).get(3, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalDuringDecodeDropsBothMessagesAndProtocolErrorReplies(boolean failDecode)
            throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var delivered = new AtomicInteger();
        RpcCodec blocking =
                new RpcCodec() {
                    public ByteBuf encode(RpcMessage message) {
                        return codec.encode(message);
                    }

                    public RpcMessage decode(ByteBuf input) {
                        var message = codec.decode(input);
                        if (message instanceof RpcRequest request) {
                            entered.countDown();
                            try {
                                if (!resume.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Not released");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(e);
                            }
                            if (failDecode)
                                throw new RpcProtocolException(
                                        "Injected decode failure",
                                        null,
                                        request.requestId(),
                                        false,
                                        request.routeKey());
                        }
                        return message;
                    }
                };
        var node =
                fixture.builder(10, (c, m) -> delivered.incrementAndGet()).codec(blocking).build();
        fixture.nodes.add(node);
        var remote = fixture.node(20, (c, m) -> {});
        fixture.connect(remote, node, 1);
        var receiving = (Network.Conn) node.peer(20).connection(0);
        var frame = codec.request(1, 1, RpcOptions.DEFAULT, body("old"));
        try (var workers = Executors.newSingleThreadExecutor()) {
            var task =
                    workers.submit(
                            () -> {
                                receiving.handler.onMessage(receiving, frame);
                                return null;
                            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var oldPeer = node.peer(20);
                node.removePeer(20);
                // Establish a replacement lifecycle while the old decoder is still blocked.
                await(() -> node.peer(20) != null && node.peer(20).isReady());
                assertNotSame(oldPeer, node.peer(20));
                int sent = fixture.network.transports.get(10).sent.size();
                resume.countDown();
                task.get(3, TimeUnit.SECONDS);
                assertEquals(0, delivered.get());
                assertEquals(sent, fixture.network.transports.get(10).sent.size());
            } finally {
                resume.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectionCloseFailuresDoNotSkipCleanupOrPendingNotifications(boolean removeOnly)
            throws Exception {
        var node = fixture.node(10, (c, m) -> {});
        var remote = fixture.node(20, (c, m) -> {});
        fixture.connect(node, remote, 2);
        var transport = fixture.network.transports.get(10);
        transport.dropBusiness = true;
        var result =
                fixture.call(
                        node, 20, RpcOptions.builder().timeout(Duration.ofSeconds(30)).build());
        var peer = node.peer(20);
        var pending = peer.pendingSnapshot().getFirst();
        var first = (Network.Conn) node.peer(20).connection(0);
        var second = node.peer(20).connection(1);
        var failure = new IllegalStateException("Injected connection close failure");
        first.closeFailure = failure;
        var transportFailure = new IllegalStateException("Injected transport close failure");
        if (!removeOnly)
            transport.closeHook =
                    () -> {
                        assertTrue(pending.isDone());
                        assertFalse(result.isDone(), "Notify only after all resource cleanup");
                        throw transportFailure;
                    };
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> {
                            if (removeOnly) node.removePeer(20);
                            else node.close();
                        }));
        assertFalse(second.isActive(), "One close failure must not skip other connections");
        assertEquals(RpcError.UNAVAILABLE, result.get(3, TimeUnit.SECONDS).error());
        assertEquals(0, peer.pendingCount());
        assertEquals(1L, node.eventCounts().get("call-failure"));
        if (removeOnly) {
            assertTrue(node.isRunning());
            assertFalse(transport.closed);
            first.close();
        } else {
            assertTrue(transport.closed);
            assertTrue(transport.connections.isEmpty());
            assertArrayEquals(new Throwable[] {transportFailure}, failure.getSuppressed());
            assertThrows(
                    IllegalStateException.class,
                    () -> node.timer.newTimeout(t -> {}, 1, TimeUnit.SECONDS));
            assertDoesNotThrow(node::close);
        }
    }

    private Network.Conn incoming(RpcNode node) throws Exception {
        var transport = fixture.network.transports.get(node.nodeId());
        var c = fixture.network.new Conn(transport, true);
        transport.connections.add(c);
        c.handler.onConnected(c);
        return c;
    }
}
