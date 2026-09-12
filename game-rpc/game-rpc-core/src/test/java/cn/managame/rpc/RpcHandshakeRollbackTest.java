package cn.managame.rpc;

import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class RpcHandshakeRollbackTest {
    final RpcNodeTest fixture = new RpcNodeTest();
    final TestCodec wire = new TestCodec(ALLOCATOR, LIMITS);
    Consumer<RpcHandshake> onAck = h -> {};

    @AfterEach
    void close() {
        fixture.close();
    }

    enum Failure {
        UNAVAILABLE,
        OVERLOADED,
        ENCODE,
        EXPIRE,
        DISCONNECT
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void failedFirstHandshakeReturnsPeerCapacity(Failure failure) throws Exception {
        var node = node();
        var connection = incoming(node);
        var reservation = new AtomicReference<RpcPeer>();
        onAck =
                h -> {
                    var peer = node.peer(20);
                    reservation.set(peer);
                    // The provisional identity cannot accumulate Calls that rollback would abandon.
                    var result = fixture.call(node, 20, RpcOptions.DEFAULT);
                    assertEquals(RpcError.UNAVAILABLE, result.join().error());
                    assertEquals(0, peer.pendingCount());
                    assertEquals(0, node.calls.requestIdCounter.get());
                    switch (failure) {
                        case UNAVAILABLE ->
                                fixture.network.transports.get(10).rejectNext =
                                        RpcTransport.Submission.UNAVAILABLE;
                        case OVERLOADED ->
                                fixture.network.transports.get(10).rejectNext =
                                        RpcTransport.Submission.OVERLOADED;
                        case ENCODE ->
                                throw new RpcProtocolException("Injected ACK encode failure");
                        case EXPIRE -> {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(e);
                            }
                        }
                        case DISCONNECT -> connection.close();
                    }
                };
        hello(connection, 20, 0, 1);
        assertNotNull(reservation.get());
        assertTrue(reservation.get().isClosed());
        assertFalse(connection.isActive());
        assertNull(node.peer(20));

        onAck = h -> {};
        var other = incoming(node);
        hello(other, 30, 0, 1);
        assertTrue(node.peer(30).isReady(), "Failed handshake must return maxPeers capacity");
        node.removePeer(30);

        var replacement = incoming(node);
        hello(replacement, 20, 0, 1);
        var peer = node.peer(20);
        assertNotSame(reservation.get(), peer);
        connection.handler.onDisconnected(connection);
        assertSame(peer, node.peer(20));
        assertTrue(peer.isReady());
    }

    @Test
    void oneSuccessfulSlotKeepsThePeerThroughDisconnectAndLaterHandshakeFailure() throws Exception {
        var node = node();
        var first = incoming(node);
        hello(first, 20, 0, 2);
        var peer = node.peer(20);
        assertFalse(peer.isReady());
        first.close();
        assertSame(peer, node.peer(20));

        var failed = incoming(node);
        fixture.network.transports.get(10).rejectNext = RpcTransport.Submission.UNAVAILABLE;
        hello(failed, 20, 1, 2);
        assertFalse(failed.isActive());
        assertSame(peer, node.peer(20));
        assertFalse(peer.isClosed());
        hello(incoming(node), 20, 0, 2);
        hello(incoming(node), 20, 1, 2);
        assertSame(peer, node.peer(20));
        assertTrue(peer.isReady());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reservationRemainsUntilTheLastCandidateSucceedsOrFails(boolean failLast) throws Exception {
        var node = node();
        var first = incoming(node);
        var second = incoming(node);
        var reservation = new AtomicReference<RpcPeer>();
        onAck =
                h -> {
                    if (h.slotIndex() == 1) {
                        fixture.network.transports.get(10).rejectNext =
                                RpcTransport.Submission.UNAVAILABLE;
                        return;
                    }
                    var peer = node.peer(20);
                    reservation.set(peer);
                    // Reentrant provider work exercises two candidates sharing a provisional Peer.
                    try {
                        hello(second, 20, 1, 2);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    assertFalse(second.isActive());
                    assertSame(peer, node.peer(20));
                    assertFalse(peer.isClosed());
                    if (failLast)
                        fixture.network.transports.get(10).rejectNext =
                                RpcTransport.Submission.UNAVAILABLE;
                };
        hello(first, 20, 0, 2);
        if (failLast) {
            assertNull(node.peer(20));
            assertTrue(reservation.get().isClosed());
        } else {
            assertSame(reservation.get(), node.peer(20));
            assertSame(first, node.peer(20).connection(0));
            first.close();
            assertSame(reservation.get(), node.peer(20));
        }
    }

    @Test
    void failedActiveConnectionKeepsItsConfiguredPeerAndReconnectIntent() throws Exception {
        var node = node();
        node.connect(20, "127.0.0.1", 25000);
        var peer = node.peer(20);
        await(() -> node.eventCounts().getOrDefault("connect-failed", 0L) > 0);
        assertSame(peer, node.peer(20));
        assertFalse(peer.isClosed());
        assertEquals(
                RpcError.UNAVAILABLE, fixture.call(node, 20, RpcOptions.DEFAULT).join().error());
        var remote = fixture.builder(20, (c, m) -> {}).listen("127.0.0.1", 25000).build();
        fixture.nodes.add(remote);
        remote.start();
        await(peer::isReady);
        assertSame(peer, node.peer(20));
    }

    private RpcNode node() {
        RpcCodec codec =
                new RpcCodec() {
                    public ByteBuf encode(RpcMessage message) {
                        if (message instanceof RpcHandshake h && h.kind() == RpcHandshake.Kind.ACK)
                            onAck.accept(h);
                        return wire.encode(message);
                    }

                    public RpcMessage decode(ByteBuf input) {
                        return wire.decode(input);
                    }
                };
        var node =
                fixture.builder(10, (c, m) -> {})
                        .maxPeers(1)
                        .handshakeTimeout(Duration.ofMillis(100))
                        .codec(codec)
                        .build();
        fixture.nodes.add(node);
        node.start();
        return node;
    }

    private Network.Conn incoming(RpcNode node) throws Exception {
        var transport = fixture.network.transports.get(node.nodeId());
        var connection = fixture.network.new Conn(transport, true);
        transport.connections.add(connection);
        connection.handler.onConnected(connection);
        return connection;
    }

    private void hello(Network.Conn connection, int source, int slot, int count) throws Exception {
        connection.handler.onMessage(connection, wire.handshake((byte) 4, source, 10, slot, count));
    }
}
