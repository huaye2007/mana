package cn.managame.rpc.node;

import cn.managame.network.connection.WriteStatus;
import cn.managame.rpc.call.*;
import cn.managame.rpc.error.RpcEncodeException;
import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import io.netty.buffer.*;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.managame.rpc.error.RpcErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class RpcResilienceTest extends RpcTestSupport {
    private static final RpcCallback<Object> CALLBACK = value -> {};

    @Test void nodeWideAdmissionBoundsConcurrentCallsAndReleasesAcrossPeers() throws Exception {
        Probe p = new Probe();
        RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p).maxPendingCalls(8).build();
        n.start();
        try (n; ExecutorService senders = Executors.newFixedThreadPool(8)) {
            Fake a = bind(n, 2, 0, 1), b = bind(n, 3, 0, 1);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                int target = i % 2 + 2;
                calls.add(senders.submit(() -> {
                    try { go.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    ByteBuf body = Unpooled.buffer().writeByte(1);
                    n.call(target, new RpcRequest(1, body), 10000, CALLBACK);
                    assertEquals(0, body.refCnt());
                }));
            }
            go.countDown();
            for (Future<?> call : calls) call.get(5, TimeUnit.SECONDS);
            assertEquals(8, n.admittedCalls.get());
            assertEquals(8, a.frames.size() + b.frames.size());
            assertEquals(56, p.failures.size());
            assertTrue(p.failures.stream().allMatch(e -> e == UNAVAILABLE));
            Fake source = a.frames.isEmpty() ? b : a;
            int target = source == a ? 2 : 3;
            int id = java.nio.ByteBuffer.wrap(source.frames.getFirst()).getInt(9);
            receive(n, source, RpcWire.encodeResponse(new RpcResponse(id, 0, null, null), 1024));
            assertEquals(7, n.admittedCalls.get());
            n.call(target, new RpcRequest(2, null), 10000, CALLBACK);
            assertEquals(8, n.admittedCalls.get());
            n.removePeer(2); n.removePeer(3);
            assertEquals(0, n.admittedCalls.get());
        }
    }

    @Test void responseNotificationRetainsAdmissionUntilHandlerReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Probe p = new Probe() {
            @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                super.onResponse(source, command, response, callback);
            }
        };
        RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p).maxPendingCalls(1).build();
        n.start();
        ExecutorService receiver = Executors.newSingleThreadExecutor();
        try {
            Fake c = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(1, null), CALLBACK);
            int id = n.peers.get(2).pending.keys().nextElement();
            Future<?> response = receiver.submit(() -> receive(n, c,
                    RpcWire.encodeResponse(new RpcResponse(id, 0, null, null), 1024)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(n.peers.get(2).pending.isEmpty());
            n.call(2, new RpcRequest(2, null), CALLBACK);
            assertEquals(UNAVAILABLE, take(p.failures));
            release.countDown(); response.get(5, TimeUnit.SECONDS);
            n.call(2, new RpcRequest(3, null), CALLBACK);
            assertEquals(1, n.admittedCalls.get()); assertEquals(2, c.frames.size());
        } finally { release.countDown(); receiver.close(); n.close(); }
    }
    @Test void encodingAndSendRejectionDoNotLeakAdmission() {
        Probe p = new Probe();
        RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p)
                .maxFrameSize(64).maxPendingCalls(1).build();
        n.start();
        try (n) {
            Fake c = bind(n, 2, 0, 1);
            ByteBuf large = Unpooled.buffer().writeZero(128);
            assertThrows(RpcEncodeException.class, () -> n.call(2, new RpcRequest(1, large), CALLBACK));
            assertEquals(0, large.refCnt()); assertEquals(0, n.admittedCalls.get());
            c.status = WriteStatus.NOT_WRITABLE;
            n.call(2, new RpcRequest(1, null), CALLBACK);
            assertEquals(UNAVAILABLE, p.failures.remove()); assertEquals(0, n.admittedCalls.get());
            c.status = WriteStatus.ACCEPTED;
            c.onWrite = frame -> { throw new IllegalStateException("write failure"); };
            assertThrows(IllegalStateException.class, () -> n.call(2, new RpcRequest(1, null), CALLBACK));
            assertEquals(0, n.admittedCalls.get());
            n.disconnected(c);
            bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(2, null), CALLBACK);
            assertEquals(1, n.admittedCalls.get());
        }
        assertEquals(0, n.admittedCalls.get());
    }

    @Test void passiveRecreationDoesNotReuseCallIdsOrAdmitOldReplies() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            Fake old = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(101, null), 150, CALLBACK);
            int oldId = n.peers.get(2).pending.keys().nextElement();
            old.close(); n.disconnected(old);
            assertEquals(TIMEOUT, take(p.failures));
            await(() -> !n.peers.containsKey(2) && n.admittedCalls.get() == 0);
            Fake fresh = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(202, null), CALLBACK);
            int newId = n.peers.get(2).pending.keys().nextElement();
            assertNotEquals(oldId, newId);
            receive(n, fresh, RpcWire.encodeResponse(new RpcResponse(oldId, 0, null, null), 1024));
            assertTrue(p.responses.isEmpty()); assertEquals(1, n.admittedCalls.get());
            receive(n, fresh, RpcWire.encodeResponse(new RpcResponse(newId, 0, null, null), 1024));
            assertEquals(202, take(p.responses).command());
            assertEquals(0, n.admittedCalls.get());
        }
    }

    @Test void blockedTimeoutNotificationLeavesOtherDeadlinesLiveAndCloseWaits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean(), closeRejected = new AtomicBoolean();
        Probe p = new Probe() {
            @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
                if (command == 1 && error == TIMEOUT) {
                    virtual.set(Thread.currentThread().isVirtual());
                    try { node.close(); } catch (IllegalStateException expected) { closeRejected.set(true); }
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                super.onFail(target, command, error, callback);
            }
        };
        RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p)
                .handshakeTimeout(Duration.ofMillis(60)).maxPendingCalls(2).build();
        p.node = n; n.start();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(1, null), 30, CALLBACK);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Fake silent = new Fake(); n.connected(silent);
            n.call(2, new RpcRequest(2, null), 40, CALLBACK);
            assertEquals(TIMEOUT, take(p.failures));
            await(() -> !silent.active && n.admittedCalls.get() == 1);
            n.call(2, new RpcRequest(3, null), CALLBACK);
            ByteBuf rejected = Unpooled.buffer().writeByte(1);
            n.call(2, new RpcRequest(4, rejected), CALLBACK);
            assertEquals(UNAVAILABLE, take(p.failures)); assertEquals(0, rejected.refCnt());
            n.removePeer(2);
            assertEquals(PEER_REMOVED, take(p.failures));
            Future<?> closing = closer.submit(n::close);
            Thread.sleep(80);
            assertFalse(closing.isDone());
            release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(TIMEOUT, take(p.failures));
            assertTrue(virtual.get()); assertTrue(closeRejected.get());
            assertEquals(0, n.admittedCalls.get()); assertTrue(p.failures.isEmpty());
        } finally { release.countDown(); n.close(); closer.close(); }
    }

    @Test void removePeerOnlyTouchesItsConnections() {
        try (RpcNode n = node(1, new Probe())) {
            Fake own = bind(n, 2, 0, 1);
            Fake unrelated = new Fake() {
                boolean inspect;
                @Override public <T> T get(AttributeKey<T> key) {
                    if (inspect) fail("removePeer inspected an unrelated connection");
                    return super.get(key);
                }
                @Override public <T> void set(AttributeKey<T> key, T value) {
                    super.set(key, value); inspect = true;
                }
            };
            n.connected(unrelated);
            n.removePeer(2);
            assertFalse(own.active); assertTrue(unrelated.active);
        }
    }

    @Test void removePeerClosesOutboundUnfinishedHandshake() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             RpcNode n = node(1, new Probe())) {
            silentServer.setSoTimeout(5000);
            n.addPeer(2, new InetSocketAddress("127.0.0.1", silentServer.getLocalPort()), 1);
            try (Socket socket = silentServer.accept()) {
                socket.setSoTimeout(5000);
                int length = new java.io.DataInputStream(socket.getInputStream()).readInt();
                assertEquals(length, socket.getInputStream().readNBytes(length).length);
                RpcPeer peer = n.peers.get(2);
                assertNull(peer.slots[0].connection.get());
                n.removePeer(2);
                assertEquals(-1, socket.getInputStream().read());
                assertTrue(peer.connections.isEmpty());
            }
        }
    }

    @Test void reconnectSpreadAndAdmissionBuilderBoundaries() {
        Probe p = new Probe();
        RpcNodeBuilder builder = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p)
                .reconnectDelay(Duration.ofMillis(100));
        try (RpcNode spread = builder.build();
             RpcNode fixed = builder.reconnectJitter(Duration.ZERO).build();
             RpcNode custom = builder.reconnectJitter(Duration.ofMillis(80)).build()) {
            for (int i = 0; i < 100; i++) {
                long delay = spread.nextReconnectDelayMillis();
                assertTrue(delay >= 100 && delay <= 125);
                assertEquals(100, fixed.nextReconnectDelayMillis());
                delay = custom.nextReconnectDelayMillis();
                assertTrue(delay >= 100 && delay <= 180);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> builder.maxPendingCalls(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxPendingCalls(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.reconnectJitter(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder.reconnectJitter(Duration.ofMillis(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> builder
                .reconnectDelay(Duration.ofMillis(Long.MAX_VALUE / 1_000_000))
                .reconnectJitter(Duration.ofMillis(1)).build());
    }
}