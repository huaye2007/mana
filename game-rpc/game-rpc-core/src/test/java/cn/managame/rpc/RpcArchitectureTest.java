package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class RpcArchitectureTest {
    final RpcNodeTest fixture = new RpcNodeTest();
    final TestCodec wire = new TestCodec(ALLOCATOR, LIMITS);

    @AfterEach
    void close() {
        fixture.close();
    }

    enum InvalidMessage {
        COMMAND,
        BUSINESS,
        REQUEST_ID,
        RESPONSE_ID,
        ERROR_BODY,
        REQUEST_METADATA,
        RESPONSE_METADATA,
        BODY
    }

    @ParameterizedTest
    @EnumSource(InvalidMessage.class)
    void customCodecCannotBypassEnvelopeRules(InvalidMessage invalid) {
        var metadata = RpcMetadata.builder().putInt((short) 1, 42).build();
        RpcMessage message =
                switch (invalid) {
                    case COMMAND -> new RpcRequest(0, 7, RpcOptions.DEFAULT, body("x"));
                    case BUSINESS ->
                            new RpcRequest(1, 7, 0, 1, (byte) 0, RpcMetadata.EMPTY, body("x"));
                    case REQUEST_ID -> new RpcRequest(1, -1, RpcOptions.DEFAULT, body("x"));
                    case RESPONSE_ID -> new RpcResponse(0, 0, RpcMetadata.EMPTY, body("x"));
                    case ERROR_BODY ->
                            new RpcResponse(
                                    7,
                                    RpcError.INTERNAL_ERROR.code(),
                                    RpcMetadata.EMPTY,
                                    body("x"));
                    case REQUEST_METADATA ->
                            new RpcRequest(1, 7, 0, 0, (byte) 0, metadata, body("x"));
                    case RESPONSE_METADATA -> new RpcResponse(7, 0, metadata, body("x"));
                    case BODY -> new RpcRequest(1, 7, RpcOptions.DEFAULT, body("too long"));
                };
        var encoded = new AtomicInteger();
        var codec =
                new RpcCodec() {
                    public ByteBuf encode(RpcMessage value) {
                        encoded.incrementAndGet();
                        return body("wire");
                    }

                    public RpcMessage decode(ByteBuf input) {
                        return message;
                    }
                };
        var handler = new RpcCodecHandler(codec, new RpcLimits(4096, 0, 4, 10));
        var encodeFailure = assertThrows(RpcProtocolException.class, () -> handler.encode(message));
        var decodeFailure =
                assertThrows(RpcProtocolException.class, () -> handler.decode(body("wire")));
        assertEquals(0, encoded.get(), "Reject before invoking a custom encoder");
        int expectedId =
                invalid == InvalidMessage.REQUEST_ID || invalid == InvalidMessage.RESPONSE_ID
                        ? 0
                        : 7;
        assertEquals(expectedId, encodeFailure.requestId());
        assertEquals(expectedId, decodeFailure.requestId());
        if (expectedId != 0)
            assertEquals(message instanceof RpcResponse, decodeFailure.isResponse());
    }

    @Test
    void nodeMetadataLimitAppliesToExplicitSendReplyAndIncomingFrames() throws Exception {
        var delivered = new AtomicInteger();
        var node =
                fixture.builder(10, (c, m) -> delivered.incrementAndGet())
                        .limits(new RpcLimits(4096, 0, 4000, 10))
                        .codec(wire)
                        .build();
        fixture.nodes.add(node);
        var remote = fixture.node(20, (c, m) -> {});
        fixture.connect(node, remote, 1);
        var metadata = RpcMetadata.builder().putInt((short) 1, 42).build();
        var request = new RpcRequest(1, 7, 123, 0, (byte) 0, metadata, body("x"));
        var transport = fixture.network.transports.get(10);
        int before = transport.sent.size();
        assertFalse(node.send(20, request, RpcOptions.DEFAULT));
        assertEquals(before, transport.sent.size());
        var connection = (Network.Conn) node.peer(20).connection(0);
        assertTrue(node.reply(connection, new RpcResponse(7, 0, metadata, body("x"))));
        var fallback = (RpcResponse) wire.decode(raw(transport.sent.getLast()));
        assertEquals(RpcError.INTERNAL_ERROR.code(), fallback.errorCode());
        assertEquals(0, fallback.metadataLength());
        connection.handler.onMessage(connection, wire.encode(request));
        assertEquals(0, delivered.get());
        var rejection = (RpcResponse) wire.decode(raw(transport.sent.getLast()));
        assertEquals(7, rejection.requestId());
        assertEquals(RpcError.PROTOCOL_ERROR.code(), rejection.errorCode());
    }

    enum BlockedHandshake {
        ADMISSION,
        ACK_ENCODE
    }

    @ParameterizedTest
    @EnumSource(BlockedHandshake.class)
    void blockedHandshakeDoesNotLockOtherPeersAndCannotReviveAfterRemoval(BlockedHandshake blocked)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var codec =
                new DefaultRpcCodec(ALLOCATOR, LIMITS) {
                    public ByteBuf encode(RpcMessage message) {
                        if (blocked == BlockedHandshake.ACK_ENCODE
                                && message instanceof RpcHandshake h
                                && h.kind() == RpcHandshake.Kind.ACK
                                && h.targetNodeId() == 20) block(entered, release);
                        return super.encode(message);
                    }
                };
        var node =
                fixture.builder(10, (c, m) -> {})
                        .codec(codec)
                        .handshakeTimeout(Duration.ofSeconds(10))
                        .peerAdmission(
                                id -> {
                                    if (blocked == BlockedHandshake.ADMISSION && id == 20)
                                        block(entered, release);
                                    return true;
                                })
                        .build();
        fixture.nodes.add(node);
        node.start();
        var first = incoming(node);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var handshake =
                    workers.submit(
                            () -> {
                                hello(first, 20);
                                return null;
                            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var reserved = node.peer(20);
                assertNotNull(reserved);
                assertFalse(reserved.isReady());
                workers.submit(
                                () -> {
                                    var other = incoming(node);
                                    hello(other, 30);
                                    assertTrue(node.peer(30).isReady());
                                    node.removePeer(20);
                                    return null;
                                })
                        .get(2, TimeUnit.SECONDS);
                assertTrue(reserved.isClosed());
                assertFalse(first.isActive());
                int sent = fixture.network.transports.get(10).sent.size();
                release.countDown();
                handshake.get(2, TimeUnit.SECONDS);
                assertNull(node.peer(20));
                assertEquals(
                        sent,
                        fixture.network.transports.get(10).sent.size(),
                        "Old ACK must not be submitted");
                var replacement = incoming(node);
                hello(replacement, 20);
                assertNotSame(reserved, node.peer(20));
                assertTrue(node.peer(20).isReady());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void blockedConnectionDiagnosticDoesNotBlockConnectionManagement() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var node =
                fixture.builder(10, (c, m) -> {})
                        .diagnostics(
                                event -> {
                                    if (event.event().equals("connection-ready"))
                                        block(entered, release);
                                })
                        .build();
        fixture.nodes.add(node);
        node.start();
        try (var worker = Executors.newFixedThreadPool(2)) {
            try {
                var receiving =
                        worker.submit(
                                () -> {
                                    hello(incoming(node), 20);
                                    return null;
                                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(receiving.isDone(), "Diagnostic runs on the notifying thread");
                worker.submit(() -> node.removePeer(20)).get(2, TimeUnit.SECONDS);
                assertNull(node.peer(20));
                release.countDown();
                receiving.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private Network.Conn incoming(RpcNode node) throws Exception {
        var transport = fixture.network.transports.get(node.nodeId());
        var connection = fixture.network.new Conn(transport, true);
        transport.connections.add(connection);
        connection.handler.onConnected(connection);
        return connection;
    }

    private void hello(Network.Conn connection, int source) throws Exception {
        connection.handler.onMessage(connection, wire.handshake((byte) 4, source, 10, 0, 1));
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS), "Not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
