package cn.managame.rpc.node;

import cn.managame.rpc.message.*;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static cn.managame.rpc.error.RpcErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class RpcIntegrationTest extends RpcTestSupport {
    @Test void tcpBidirectionalCallsNotificationsAndIndependentReconnect() throws Exception {
        Probe pa = new Probe(), pb = new Probe();
        try (RpcNode a = node(1, pa); RpcNode b = node(2, pb)) {
            a.addPeer(2, b.localAddress(), 3);
            await(() -> a.peers.get(2).hasCandidate() && b.peers.containsKey(1));
            await(() -> java.util.Arrays.stream(a.peers.get(2).slots).allMatch(s -> s.connection.get() != null));
            pb.onRequest = r -> {
                if (r.id() != 0) assertEquals(RpcSendStatus.ACCEPTED, b.reply(r.source(), r.slot(), r.route(),
                        new RpcResponse(r.id(), 0, null, Unpooled.wrappedBuffer(r.body()))));
            };
            byte[] content = {1, 2, 3, 4};
            a.call(2, new RpcRequest(44, 2, 0, 0, null, Unpooled.wrappedBuffer(content)), value -> {});
            assertArrayEquals(content, take(pa.responses).body());
            assertEquals(2, take(pb.requests).slot());
            assertEquals(RpcSendStatus.ACCEPTED, b.notify(1, new RpcRequest(55, null)));
            assertEquals(55, take(pa.requests).command());

            var peer = a.peers.get(2);
            var broken = peer.slots[1].connection.get();
            var healthy = peer.slots[0].connection.get();
            broken.close();
            await(() -> peer.slots[1].connection.get() != null && peer.slots[1].connection.get() != broken);
            assertSame(healthy, peer.slots[0].connection.get());
            a.call(2, new RpcRequest(44, 1, 0, 0, null, null), value -> {});
            take(pa.responses);

            // Passive -> active upgrade retains the exact existing object and connections.
            RpcPeer passive = b.peers.get(1);
            b.addPeer(1, a.localAddress(), 3);
            assertSame(passive, b.peers.get(1));
            b.removePeer(1);
            await(() -> b.peers.containsKey(1) && b.peers.get(1) != passive);
            await(() -> a.peers.get(2).hasCandidate());
        }
    }

    @Test void acceptedCallSurvivesBrokenOriginalConnectionAndUsesAnotherSlot() throws Exception {
        Probe pa = new Probe(), pb = new Probe();
        try (RpcNode a = node(1, pa); RpcNode b = node(2, pb)) {
            a.addPeer(2, b.localAddress(), 2);
            await(() -> b.peers.containsKey(1)
                    && java.util.Arrays.stream(b.peers.get(1).slots).allMatch(s -> s.connection.get() != null));
            a.call(2, new RpcRequest(10, 1, 0, 0, null, null), value -> {});
            Request request = take(pb.requests);
            b.peers.get(1).slots[request.slot()].connection.get().close();
            await(() -> b.peers.get(1).slots[request.slot()].connection.get() == null);
            assertTrue(pa.failures.isEmpty());
            assertEquals(RpcSendStatus.ACCEPTED, b.reply(1, request.slot(), request.route(),
                    new RpcResponse(request.id(), 0, null, null)));
            assertEquals(request.id(), take(pa.responses).id());
        }
    }

    @Test void realHandlerFailureIsResponseAndConnectionRemainsUsable() throws Exception {
        Probe pa = new Probe(), pb = new Probe();
        try (RpcNode a = node(1, pa); RpcNode b = node(2, pb)) {
            a.addPeer(2, b.localAddress(), 1);
            await(() -> a.peers.get(2).hasCandidate());
            var original = a.peers.get(2).slots[0].connection.get();
            pb.onRequest = r -> { throw new IllegalArgumentException("intentional"); };
            a.call(2, new RpcRequest(9, null), value -> {});
            assertEquals(HANDLER_ERROR, take(pa.responses).error());
            assertTrue(pa.failures.isEmpty());
            assertSame(original, a.peers.get(2).slots[0].connection.get());
            assertEquals(RpcSendStatus.ACCEPTED, a.notify(2, new RpcRequest(9, null)));
        }
    }

    @Test void oldBusinessReplyAfterPeerRecreationCannotCompleteNewCall() throws Exception {
        Probe pa = new Probe(), pb = new Probe();
        try (RpcNode a = node(1, pa); RpcNode b = node(2, pb)) {
            a.addPeer(2, b.localAddress(), 1);
            await(() -> a.peers.get(2).hasCandidate());
            a.call(2, new RpcRequest(101, null), value -> {});
            Request old = take(pb.requests);
            a.removePeer(2);
            assertEquals(PEER_REMOVED, take(pa.failures));
            await(() -> !b.peers.containsKey(1));
            a.addPeer(2, b.localAddress(), 1);
            await(() -> a.peers.get(2).hasCandidate());
            a.call(2, new RpcRequest(202, null), value -> {});
            Request fresh = take(pb.requests);
            assertNotEquals(old.id(), fresh.id());
            assertEquals(RpcSendStatus.ACCEPTED, b.reply(1, old.slot(), old.route(),
                    new RpcResponse(old.id(), 0, null, Unpooled.wrappedBuffer(new byte[] {1}))));
            assertEquals(RpcSendStatus.ACCEPTED, b.reply(1, fresh.slot(), fresh.route(),
                    new RpcResponse(fresh.id(), 0, null, Unpooled.wrappedBuffer(new byte[] {2}))));
            Result result = take(pa.responses);
            assertEquals(202, result.command()); assertEquals(fresh.id(), result.id());
            assertArrayEquals(new byte[] {2}, result.body());
            assertTrue(pa.responses.isEmpty()); assertTrue(pa.failures.isEmpty());
        }
    }
    @Test void retriesInitialConnectionFailureThenConnects() throws Exception {
        Probe pa = new Probe(), pb = new Probe();
        java.net.InetSocketAddress address;
        try (java.net.ServerSocket temporary = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            address = new java.net.InetSocketAddress("127.0.0.1", temporary.getLocalPort());
        }
        RpcNode a = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(pa)
                .reconnectDelay(Duration.ofMillis(30)).build();
        RpcNode b = RpcNode.builder().nodeId(2).bindAddress(address).handler(pb).build();
        a.start();
        try {
            a.addPeer(2, address, 1);
            Thread.sleep(80);
            assertTrue(a.peers.get(2).slots[0].connecting.get());
            b.start();
            await(() -> a.peers.get(2).hasCandidate());
            assertFalse(a.peers.get(2).slots[0].connecting.get());
        } finally { a.close(); b.close(); }
    }
}

