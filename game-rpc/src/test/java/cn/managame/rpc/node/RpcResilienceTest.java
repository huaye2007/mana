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

    @Test void stoppingRecoveryCannotLoseConcurrentUnbindOrUpgrade() throws Exception {
        try (RpcNode n = node(1, new Probe()); ExecutorService racers = Executors.newFixedThreadPool(2)) {
            RpcPeer peer = new RpcPeer(2, 1, LOCAL);
            n.peers.put(2, peer);
            ConnectionSlot slot = peer.slots[0];
            CyclicBarrier start = new CyclicBarrier(3), end = new CyclicBarrier(3);
            java.util.concurrent.atomic.AtomicInteger owners = new java.util.concurrent.atomic.AtomicInteger();
            int rounds = 10000;
            Future<?> stopper = racers.submit(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        start.await();
                        if (n.continueRecovery(peer, slot)) owners.incrementAndGet();
                        end.await();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Future<?> unbinder = racers.submit(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        start.await();
                        if (peer.target == null) peer.target = LOCAL;
                        else slot.connection.set(null);
                        if (slot.connecting.compareAndSet(false, true)) owners.incrementAndGet();
                        end.await();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            boolean missing = false, duplicate = false;
            for (int i = 0; i < rounds; i++) {
                peer.target = i % 2 == 0 ? LOCAL : null;
                slot.connection.set(i % 2 == 0 ? new Fake() : null);
                slot.connecting.set(true); owners.set(0);
                start.await(5, TimeUnit.SECONDS); end.await(5, TimeUnit.SECONDS);
                missing |= !slot.connecting.get() || owners.get() == 0;
                duplicate |= owners.get() > 1;
            }
            stopper.get(5, TimeUnit.SECONDS); unbinder.get(5, TimeUnit.SECONDS);
            assertFalse(missing, "Empty Slot lost its recovery chain");
            assertFalse(duplicate, "More than one recovery chain owns the Slot");
        }
    }

    @Test void encodingAndSendRejectionDoNotLeakPendingCalls() {
        Probe p = new Probe();
        RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p)
                .maxFrameSize(64).build();
        n.start();
        try (n) {
            Fake c = bind(n, 2, 0, 1);
            ByteBuf large = Unpooled.buffer().writeZero(128);
            assertThrows(RpcEncodeException.class, () -> n.call(2, new RpcRequest(1, large), CALLBACK));
            assertEquals(0, large.refCnt()); assertTrue(n.peers.values().stream().allMatch(peer -> peer.pending.isEmpty()));
            c.status = WriteStatus.NOT_WRITABLE;
            n.call(2, new RpcRequest(1, null), CALLBACK);
            assertEquals(UNAVAILABLE, p.failures.remove()); assertTrue(n.peers.values().stream().allMatch(peer -> peer.pending.isEmpty()));
            c.status = WriteStatus.ACCEPTED;
            c.onWrite = frame -> { throw new IllegalStateException("write failure"); };
            assertThrows(IllegalStateException.class, () -> n.call(2, new RpcRequest(1, null), CALLBACK));
            assertTrue(n.peers.values().stream().allMatch(peer -> peer.pending.isEmpty()));
            n.disconnected(c);
            bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(2, null), CALLBACK);
            assertEquals(1, n.peers.get(2).pending.size());
        }
        assertTrue(n.peers.values().stream().allMatch(peer -> peer.pending.isEmpty()));
    }

    @Test void passiveRecreationDoesNotReuseCallIdsOrAdmitOldReplies() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            Fake old = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(101, null), 150, CALLBACK);
            int oldId = n.peers.get(2).pending.keys().nextElement();
            old.close(); n.disconnected(old);
            assertEquals(TIMEOUT, take(p.failures));
            await(() -> !n.peers.containsKey(2));
            Fake fresh = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(202, null), CALLBACK);
            int newId = n.peers.get(2).pending.keys().nextElement();
            assertNotEquals(oldId, newId);
            receive(n, fresh, RpcWire.encodeResponse(new RpcResponse(oldId, 0, null, null), 1024));
            assertTrue(p.responses.isEmpty()); assertEquals(1, n.peers.get(2).pending.size());
            receive(n, fresh, RpcWire.encodeResponse(new RpcResponse(newId, 0, null, null), 1024));
            assertEquals(202, take(p.responses).command());
            assertTrue(n.peers.values().stream().allMatch(peer -> peer.pending.isEmpty()));
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
                .handshakeTimeout(Duration.ofMillis(60)).build();
        p.node = n; n.start();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(1, null), 30, CALLBACK);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Fake silent = new Fake(); n.connected(silent);
            n.call(2, new RpcRequest(2, null), 40, CALLBACK);
            assertEquals(TIMEOUT, take(p.failures));
            await(() -> !silent.active);
            n.call(2, new RpcRequest(3, null), CALLBACK);
            n.removePeer(2);
            assertEquals(PEER_REMOVED, take(p.failures));
            Future<?> closing = closer.submit(n::close);
            Thread.sleep(80);
            assertFalse(closing.isDone());
            release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(TIMEOUT, take(p.failures));
            assertTrue(virtual.get()); assertTrue(closeRejected.get());
            assertTrue(n.peers.values().stream().allMatch(peer -> peer.pending.isEmpty())); assertTrue(p.failures.isEmpty());
        } finally { release.countDown(); n.close(); closer.close(); }
    }

    @Test void removePeerOnlyTouchesItsConnections() {
        try (RpcNode n = node(1, new Probe())) {
            Fake own = bind(n, 2, 0, 1);
            Fake unrelated = new Fake();
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
                assertFalse(n.peers.containsKey(2));
            }
        }
    }

    @Test void reconnectSpreadAndBuilderBoundaries() {
        Probe p = new Probe();
        RpcNodeBuilder builder = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p)
                .reconnectDelay(Duration.ofMillis(100));
        try (RpcNode spread = builder.build();
             RpcNode fixed = builder.reconnectRandomDelay(Duration.ZERO).build();
             RpcNode custom = builder.reconnectRandomDelay(Duration.ofMillis(80)).build()) {
            for (int i = 0; i < 100; i++) {
                long delay = spread.nextReconnectDelayMillis();
                assertTrue(delay >= 100 && delay <= 125);
                assertEquals(100, fixed.nextReconnectDelayMillis());
                delay = custom.nextReconnectDelayMillis();
                assertTrue(delay >= 100 && delay <= 180);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> builder.reconnectRandomDelay(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder.reconnectRandomDelay(Duration.ofMillis(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> builder
                .reconnectDelay(Duration.ofMillis(Long.MAX_VALUE / 1_000_000))
                .reconnectRandomDelay(Duration.ofMillis(1)).build());
    }
}
