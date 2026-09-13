package cn.managame.rpc.core;

import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcHandshake;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRouteMessage;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcDirectPeerTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    RpcNode add(RpcNode.Builder builder) {
        var node = builder.build();
        fixture.nodes.add(node);
        return node;
    }

    @Test
    void unknownTargetFailsWithoutCreatingPeerEncodingOrAdvancingRequestId() throws Exception {
        var codec = new RpcHotPathTest.CountingCodec();
        var a = add(fixture.builder(10, (c, m) -> {}).codec(codec));
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        assertEquals(RpcError.UNAVAILABLE, fixture.call(a, 99, RpcOptions.DEFAULT).get().error());
        assertFalse(a.send(99, 7, body("unknown"), RpcOptions.DEFAULT));
        assertFalse(a.send(99, new RpcRouteMessage(10, 20, body("opaque")), RpcOptions.DEFAULT));
        assertNull(a.peer(99));
        assertEquals(0, codec.encoded);
        assertEquals(0, a.calls.requestIdCounter.get());
        assertTrue(a.peer(20).isReady());
    }

    @Test
    void routeEnvelopeIsDeliveredOnceWithoutInnerDecodeOrAutomaticForwarding() throws Exception {
        var decoded = new AtomicInteger();
        var received = new AtomicReference<RpcRouteMessage>();
        var codec =
                new DefaultRpcCodec(ALLOCATOR, LIMITS) {
                    public RpcMessage decode(ByteBuf input) {
                        var msg = super.decode(input);
                        if (!(msg instanceof RpcHandshake)) decoded.incrementAndGet();
                        return msg;
                    }
                };
        var a = fixture.node(10, (c, m) -> {});
        var middle =
                add(fixture.builder(30, (c, m) -> received.set((RpcRouteMessage) m)).codec(codec));
        var atTarget = new AtomicInteger();
        var target = fixture.node(20, (c, m) -> atTarget.incrementAndGet());
        fixture.connect(a, middle, 1);
        fixture.connect(middle, target, 1);
        for (int destination : new int[] {20, 30}) {
            assertTrue(
                    a.send(
                            30,
                            new RpcRouteMessage(987, destination, raw(new byte[] {99, 0, 1})),
                            RpcOptions.DEFAULT));
            assertEquals(987, received.get().sourceNodeId());
            assertEquals(destination, received.get().targetNodeId());
            assertEquals(raw(new byte[] {99, 0, 1}), received.get().inner());
        }
        assertEquals(2, decoded.get());
        assertEquals(0, atTarget.get());
        assertNull(middle.peer(987));
    }

    @Test
    void onlyBareResponseFromTheConnectedTargetCompletesItsCall() throws Exception {
        var delivered = new AtomicInteger();
        var a = fixture.node(10, (c, m) -> delivered.incrementAndGet());
        var b = fixture.node(20, (c, m) -> {});
        var c = fixture.node(30, (conn, msg) -> {});
        fixture.connect(a, b, 1);
        fixture.connect(a, c, 1);
        fixture.network.transports.get(10).dropBusiness = true;
        var result = fixture.call(a, 20, RpcOptions.DEFAULT);
        int id = a.peer(20).pendingSnapshot().getFirst().requestId;
        var wire = new TestCodec(ALLOCATOR, LIMITS);
        var other = (Network.Conn) a.peer(30).connection(0);
        var response = wire.response(id, 0, RpcMetadata.EMPTY, body("ok"));
        other.handler.onMessage(other, wire.route(20, 10, response));
        assertEquals(1, delivered.get());
        assertFalse(result.isDone());
        other.handler.onMessage(other, response);
        assertFalse(result.isDone());
        var correct = (Network.Conn) a.peer(20).connection(0);
        correct.handler.onMessage(correct, response);
        assertEquals(body("ok"), result.get().value().body());
    }

    @Test
    void reconnectedPeerDoesNotReuseRemovedPeersCallId() throws Exception {
        var a = fixture.node(10, (c, m) -> {});
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 1);
        fixture.network.transports.get(10).dropBusiness = true;
        var oldCall = fixture.call(a, 20, RpcOptions.DEFAULT);
        var old = a.peer(20);
        int oldId = old.pendingSnapshot().getFirst().requestId;
        a.removePeer(20);
        assertEquals(RpcError.UNAVAILABLE, oldCall.get().error());
        fixture.connect(a, b, 1);
        var fresh = fixture.call(a, 20, RpcOptions.DEFAULT);
        int newId = a.peer(20).pendingSnapshot().getFirst().requestId;
        assertNotSame(old, a.peer(20));
        assertNotEquals(oldId, newId);
        var conn = (Network.Conn) a.peer(20).connection(0);
        var wire = new TestCodec(ALLOCATOR, LIMITS);
        conn.handler.onMessage(conn, wire.response(oldId, 0, RpcMetadata.EMPTY, body("old")));
        assertFalse(fresh.isDone());
        conn.handler.onMessage(conn, wire.response(newId, 0, RpcMetadata.EMPTY, body("new")));
        assertEquals(body("new"), fresh.get().value().body());
    }

    @Test
    void peerCapacityIsReleasedByRemovalAndDoesNotEvictConnectedNodes() throws Exception {
        var a = add(fixture.builder(10, (c, m) -> {}).maxPeers(1));
        var b = fixture.node(20, (c, m) -> {});
        var c = fixture.node(30, (conn, msg) -> {});
        fixture.connect(a, b, 1);
        c.start();
        assertThrows(
                RpcException.class, () -> a.connect(30, "127.0.0.1", c.localAddress().getPort()));
        assertNull(a.peer(30));
        assertTrue(a.peer(20).isReady());
        a.removePeer(20);
        fixture.connect(a, c, 1);
        assertTrue(a.peer(30).isReady());
    }

    @Test
    void explicitEnvelopeUsesTheSameBackpressureAndBufferOwnershipPath() throws Exception {
        var codec = new RpcHotPathTest.CountingCodec();
        var a = add(fixture.builder(10, (c, m) -> {}).codec(codec));
        var b = fixture.node(20, (c, m) -> {});
        fixture.connect(a, b, 2);
        fixture.network.transports.get(10).dropBusiness = true;
        var conn = (Network.Conn) ConnectionSelector.DEFAULT.select(a.peer(20), 123);
        conn.writable = false;
        var inner = PooledByteBufAllocator.DEFAULT.buffer().writeInt(77);
        try {
            assertFalse(a.send(20, new RpcRouteMessage(999, 777, inner), RpcOptions.route(123)));
            assertEquals(0, codec.encoded);
            assertEquals(1, inner.refCnt());
            conn.writable = true;
            assertTrue(a.send(20, new RpcRouteMessage(999, 777, inner), RpcOptions.route(123)));
            assertEquals(1, codec.encoded);
            assertEquals(1, inner.refCnt());
            assertEquals(0, inner.readerIndex());
        } finally {
            inner.release();
        }
    }
}
