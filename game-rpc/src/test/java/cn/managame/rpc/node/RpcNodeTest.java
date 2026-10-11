package cn.managame.rpc.node;

import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.error.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.network.connection.WriteStatus;
import io.netty.buffer.*;
import io.netty.handler.timeout.IdleState;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static cn.managame.rpc.error.RpcErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class RpcNodeTest extends RpcTestSupport {
    static final RpcCallback<Object> CALLBACK = value -> {};

    @Test void lifecycleAndOwnershipFailures() {
        Probe p = new Probe();
        RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p).build();
        ByteBuf original = Unpooled.buffer().writeByte(1);
        assertThrows(IllegalStateException.class, () -> n.notify(2, new RpcRequest(1, original)));
        assertEquals(1, original.refCnt());
        n.start();
        try {
            assertThrows(IllegalStateException.class, n::start);
            assertEquals(RpcSendStatus.PEER_NOT_FOUND, n.notify(2, new RpcRequest(1, original)));
            assertEquals(0, original.refCnt());
            ByteBuf missing = Unpooled.buffer().writeByte(2);
            n.call(2, new RpcRequest(1, missing), CALLBACK);
            assertEquals(0, missing.refCnt());
            assertEquals(PEER_NOT_FOUND, p.failures.remove());
            ByteBuf invalid = Unpooled.buffer().writeByte(3);
            assertThrows(IllegalArgumentException.class,
                    () -> n.call(2, new RpcRequest(1, invalid), 0, CALLBACK));
            assertEquals(1, invalid.refCnt()); invalid.release();
        } finally { n.close(); }
        n.close();
        assertThrows(IllegalStateException.class, () -> n.removePeer(2));
        RpcNode neverStarted = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p).build();
        neverStarted.close();
        assertThrows(IllegalStateException.class, neverStarted::start);
    }

    @Test void routeFallbackOriginalSlotAndAdmissionStopsTraversal() {
        try (RpcNode n = node(1, new Probe())) {
            Fake zero = bind(n, 2, 0, 3), one = bind(n, 2, 1, 3), two = bind(n, 2, 2, 3);
            assertEquals(RpcSendStatus.ACCEPTED, n.notify(2, new RpcRequest(1, -1L, 0, 0, null, null)));
            assertEquals(1, zero.frames.size()); // unsigned max64 % 3 == 0
            zero.frames.clear();
            one.status = WriteStatus.NOT_WRITABLE;
            assertEquals(RpcSendStatus.ACCEPTED, n.notify(2, new RpcRequest(1, 1, 0, 0, null, null)));
            assertEquals(1, two.frames.size());
            assertEquals(0, zero.frames.size());
            two.frames.clear();
            n.reply(2, 2, 1, new RpcResponse(99, 0, null, null));
            assertEquals(1, two.frames.size()); // Original slot outranks route 1.
            two.writable = false;
            n.reply(2, 2, 1, new RpcResponse(99, 0, null, null));
            assertEquals(1, zero.frames.size()); // Preferred 2 fails; route 1 fails, then 0.
            zero.writable = false;
            ByteBuf body = Unpooled.buffer().writeByte(1);
            assertEquals(RpcSendStatus.UNAVAILABLE, n.notify(2, new RpcRequest(1, body)));
            assertEquals(0, body.refCnt());
        }
    }

    @Test void fastResponseBeforeTimerPublicationAndRemoteErrorsStayResponses() throws Exception {
        Probe p = new Probe(); p.throwResponse = true;
        try (RpcNode n = node(1, p)) {
            Fake c = bind(n, 2, 0, 1);
            c.onWrite = frame -> {
                if (frame.getUnsignedByte(4) == RpcWire.REQUEST) {
                    int id = frame.getInt(9);
                    receive(n, c, RpcWire.encodeResponse(new RpcResponse(id, HANDLER_ERROR, null, null), 1024));
                }
            };
            n.call(2, new RpcRequest(7, null), 20, CALLBACK);
            assertEquals(HANDLER_ERROR, take(p.responses).error());
            assertTrue(n.peers.get(2).pending.isEmpty());
            Thread.sleep(80);
            assertTrue(p.failures.isEmpty());
            assertTrue(c.active);
        }
    }

    @Test void malformedMatchedResponseFailsOnceButUnknownResponseIsDiscarded() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            Fake c = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(7, null), CALLBACK);
            int id = n.peers.get(2).pending.keys().nextElement();
            receive(n, c, Unpooled.buffer().writeInt(5).writeByte(RpcWire.RESPONSE).writeInt(id + 100));
            assertTrue(c.active);
            receive(n, c, Unpooled.buffer().writeInt(5).writeByte(RpcWire.RESPONSE).writeInt(id));
            assertFalse(c.active);
            assertEquals(PROTOCOL_ERROR, take(p.failures));
            assertTrue(n.peers.get(2).pending.isEmpty());
        }
    }

    @Test void disconnectKeepsPendingCrossSlotResponseCompletes() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            Fake zero = bind(n, 2, 0, 2), one = bind(n, 2, 1, 2);
            n.call(2, new RpcRequest(8, null), CALLBACK);
            int id = n.peers.get(2).pending.keys().nextElement();
            zero.close(); n.disconnected(zero);
            assertTrue(p.failures.isEmpty());
            receive(n, one, RpcWire.encodeResponse(new RpcResponse(id, 12000, null, null), 1024));
            assertEquals(12000, take(p.responses).error());
            receive(n, one, RpcWire.encodeResponse(new RpcResponse(id, 0, null, null), 1024));
            assertTrue(p.responses.isEmpty());
            one.close(); n.disconnected(one);
            assertFalse(n.peers.containsKey(2));
        }
    }

    @Test void passivePeerRetainedUntilTimeoutThenRemoved() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            Fake c = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(9, null), 30, CALLBACK);
            c.close(); n.disconnected(c);
            assertTrue(n.peers.containsKey(2));
            assertEquals(TIMEOUT, take(p.failures));
            await(() -> !n.peers.containsKey(2));
        }
    }

    @Test void onlyOneSideOfAPairMayAddPeer() {
        try (RpcNode n = node(1, new Probe())) {
            Fake c = bind(n, 2, 0, 1);
            RpcPeer before = n.peers.get(2);
            assertThrows(IllegalStateException.class, () -> n.addPeer(2, LOCAL, 1));   // 2 already dialed us
            assertSame(before, n.peers.get(2));
            assertTrue(c.active);
            // A locally dialed peer rejects an inbound handshake from the same node.
            var unreachable = new java.net.InetSocketAddress("127.0.0.1", 1);
            n.addPeer(3, unreachable, 1);
            n.addPeer(3, unreachable, 1);                                            // idempotent
            assertThrows(IllegalStateException.class, () -> n.addPeer(3, unreachable, 2));
            Fake rejected = new Fake(); n.connected(rejected);
            receive(n, rejected, RpcWire.encodeHandshake(new RpcHandshake(3, 0, 1)));
            assertFalse(rejected.active);
            assertNull(n.peers.get(3).slots[0].connection.get());
            n.removePeer(2);
            assertFalse(c.active);
            assertFalse(n.peers.containsKey(2));
            Fake inbound = bind(n, 2, 0, 2); // Removal is not a deny list.
            assertNotSame(before, n.peers.get(2));
            assertTrue(inbound.active);
        }
    }

    @Test void duplicateHandshakeSlotMismatchAndOldDisconnectNeverEvictReplacement() {
        try (RpcNode n = node(1, new Probe())) {
            Fake old = bind(n, 2, 0, 1);
            Fake duplicate = new Fake(); n.connected(duplicate);
            receive(n, duplicate, RpcWire.encodeHandshake(new RpcHandshake(2, 0, 1)));
            assertFalse(duplicate.active);
            assertSame(old, n.peers.get(2).slots[0].connection.get());
            old.close(); n.disconnected(old);
            Fake replacement = bind(n, 2, 0, 1);
            n.disconnected(old);
            assertSame(replacement, n.peers.get(2).slots[0].connection.get());
            receive(n, replacement, RpcWire.encodeHandshake(new RpcHandshake(2, 0, 1)));
            assertFalse(replacement.active);
        }
    }

    @Test void requestIdWrapCollisionDoesNotOverwriteOldCall() {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            bind(n, 2, 0, 1);
            RpcPeer peer = n.peers.get(2);
            n.requestIds.set(-1);
            n.call(2, new RpcRequest(1, null), CALLBACK);
            n.call(2, new RpcRequest(1, null), CALLBACK);
            assertEquals(Set.of(-1, 1), peer.pending.keySet());
            PendingCall old = peer.pending.get(1);
            n.requestIds.set(1);
            ByteBuf body = Unpooled.buffer().writeByte(9);
            assertThrows(RpcException.class, () -> n.call(2, new RpcRequest(1, body), CALLBACK));
            assertEquals(0, body.refCnt());
            assertSame(old, peer.pending.get(1));
            assertEquals(2, peer.pending.size());
        }
        assertEquals(2, p.failures.size());
    }

    @Test void handlerFailureRepliesWithoutClosingAndNotifyHasNoResponse() throws Exception {
        Probe p = new Probe(); p.onRequest = r -> { throw new IllegalStateException("request handler test"); };
        try (RpcNode n = node(1, p)) {
            Fake c = bind(n, 2, 0, 1);
            receive(n, c, RpcWire.encodeRequest(new RpcRequest(4, null), 15, 1024));
            assertTrue(c.active);
            ByteBuf error = Unpooled.wrappedBuffer(c.frames.getFirst());
            error.skipBytes(9);
            assertEquals(HANDLER_ERROR, RpcWire.decodeResponse(15, error).errorCode());
            error.release();
            c.frames.clear();
            receive(n, c, RpcWire.encodeRequest(new RpcRequest(4, null), 0, 1024));
            assertTrue(c.frames.isEmpty());
            assertTrue(p.failures.isEmpty());
        }
    }

    @Test void heartbeatRejectionAndHandshakeTimeoutCloseConnections() throws Exception {
        Probe p = new Probe();
        RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p)
                .handshakeTimeout(Duration.ofMillis(30)).build();
        n.start();
        try {
            Fake waiting = new Fake(); n.connected(waiting);
            n.idle(waiting, IdleState.READER_IDLE);
            assertTrue(waiting.active);
            await(() -> !waiting.active);
            Fake ready = bind(n, 2, 0, 1);
            ready.writable = false;
            n.idle(ready, IdleState.WRITER_IDLE);
            assertFalse(ready.active);
            Fake beforeHandshake = new Fake(); n.connected(beforeHandshake);
            receive(n, beforeHandshake, RpcWire.encodeHeartbeat());
            assertFalse(beforeHandshake.active);
        } finally { n.close(); }
    }

    @Test void responseTimeoutRemovalRaceCompletesExactlyOnce() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = node(1, p); ExecutorService threads = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 50; i++) {
                Fake c = bind(n, 2, 0, 1);
                n.call(2, new RpcRequest(1, null), 10, CALLBACK);
                int id = n.peers.get(2).pending.keys().nextElement();
                CountDownLatch go = new CountDownLatch(1);
                Future<?> response = threads.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    receive(n, c, RpcWire.encodeResponse(new RpcResponse(id, 0, null, null), 1024));
                });
                Future<?> removal = threads.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    n.removePeer(2);
                });
                go.countDown(); response.get(); removal.get();
                await(() -> p.responses.size() + p.failures.size() == 1);
                p.responses.clear(); p.failures.clear();
            }
            Thread.sleep(50);
            assertTrue(p.responses.isEmpty()); assertTrue(p.failures.isEmpty());
        }
    }

    @Test void closeWaitsAdmittedCallAndConcurrentClosers() throws Exception {
        Probe p = new Probe();
        RpcNode n = node(1, p);
        try (ExecutorService threads = Executors.newFixedThreadPool(3)) {
            Fake c = bind(n, 2, 0, 1);
            CountDownLatch writing = new CountDownLatch(1), proceed = new CountDownLatch(1);
            c.onWrite = frame -> {
                writing.countDown();
                try { proceed.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            };
            Future<?> call = threads.submit(() -> n.call(2, new RpcRequest(1, null), CALLBACK));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> first = threads.submit(n::close), second = threads.submit(n::close);
            Thread.sleep(30);
            assertFalse(first.isDone()); assertFalse(second.isDone());
            proceed.countDown();
            call.get(5, TimeUnit.SECONDS); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(NODE_CLOSED, take(p.failures));
            assertTrue(p.failures.isEmpty()); assertTrue(n.peers.isEmpty()); assertFalse(c.active);
        } finally { n.close(); }
    }

    @Test void closeInHandlerIsRejectedBeforeChangingLifecycle() {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            p.onRequest = r -> assertThrows(IllegalStateException.class, n::close);
            Fake c = bind(n, 2, 0, 1);
            receive(n, c, RpcWire.encodeRequest(new RpcRequest(1, null), 0, 1024));
            assertEquals(RpcSendStatus.ACCEPTED, n.notify(2, new RpcRequest(1, null)));
        }
    }

    @Test void builderSnapshotAndStartFailureAreTerminal() {
        Probe p = new Probe();
        RpcNodeBuilder b = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p);
        RpcNode first = b.build(), second = b.nodeId(2).build();
        assertEquals(1, first.nodeId()); assertEquals(2, second.nodeId());
        first.start();
        try {
            RpcNode occupied = RpcNode.builder().nodeId(3).bindAddress(first.localAddress()).handler(p).build();
            assertThrows(RpcException.class, occupied::start);
            assertThrows(IllegalStateException.class, occupied::start);
            occupied.close();
            assertThrows(IllegalArgumentException.class, () -> b.heartbeatTimeout(Duration.ofSeconds(1)).build());
            assertThrows(IllegalArgumentException.class, () -> b.callTimeout(Duration.ofNanos(1)));
        } finally { first.close(); second.close(); }
    }
}

