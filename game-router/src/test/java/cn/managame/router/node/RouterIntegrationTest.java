package cn.managame.router.node;

import cn.managame.core.Metadata;
import cn.managame.core.MetadataKey;
import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.call.RpcHandler;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.router.call.*;
import io.netty.buffer.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static cn.managame.rpc.error.RpcErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class RouterIntegrationTest {
    static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        System.setProperty("io.netty.leakDetection.level", "paranoid");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent()); Files.writeString(marker, "test-only TCP selector fallback");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    /** Records what a service or a Router's direct handler receives. Routed bodies are copied before the callback returns. */
    static class Probe implements RouterHandler, RpcHandler {
        ServiceRouting routing;
        volatile boolean echo, throwRequest;
        final BlockingQueue<RoutedRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<Result> responses = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> failures = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> direct = new LinkedBlockingQueue<>();
        @Override public void onRoutedRequest(RoutedRequest incoming) {
            RpcRequest r = incoming.request();
            ByteBuf body = r.body() == null ? null : Unpooled.copiedBuffer(r.body());
            requests.add(new RoutedRequest(incoming.sourceNodeId(), incoming.sourceNodeEpoch(),
                    new RpcRequest(r.command(), r.requestId(), r.routeKey(), r.businessIdType(), r.businessId(), r.metadata(), body),
                    incoming.routerId()));
            if (throwRequest) throw new IllegalStateException("business failure");
            if (echo && r.requestId() != 0) assertEquals(RpcSendStatus.ACCEPTED, routing.reply(incoming,
                    new RpcResponse(r.requestId(), 0, r.metadata(), r.body() == null ? null : r.body().retain())));
        }
        @Override public void onRequest(int source, int slot, RpcRequest request) { direct.add(request.command()); }
        @Override public void onResponse(int target, int command, RpcResponse response, RpcCallback<?> callback) {
            responses.add(new Result(target, command, response.errorCode(), response.metadata(),
                    response.body() == null ? new byte[0] : ByteBufUtil.getBytes(response.body())));
            @SuppressWarnings("unchecked") RpcCallback<Integer> completion = (RpcCallback<Integer>) callback;
            completion.onResponse(response.errorCode());
        }
        @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) { failures.add(error); }
        void release() {
            for (RoutedRequest r : requests) if (r.request().body() != null) r.request().body().release();
            requests.clear();
        }
    }
    record Result(int target, int command, int error, Metadata metadata, byte[] body) {}

    // Fixtures own the one RpcNode of each simulated process; routing roles never own, start or close it.
    static final class RouterFixture implements AutoCloseable {
        final RpcNode rpc;
        final GameRouter routing;
        final Probe probe = new Probe();
        RouterFixture(int id) { this(id, LOCAL); }
        RouterFixture(int id, SocketAddress address) {
            routing = new GameRouter(probe);
            rpc = RpcNode.builder().nodeId(id).bindAddress(address).handler(routing).build();
            routing.start(rpc); rpc.start();
        }
        int id() { return rpc.nodeId(); }
        public void close() { routing.close(); rpc.close(); }
    }
    static final class NodeFixture implements AutoCloseable {
        final RpcNode rpc;
        final ServiceRouting routing;
        NodeFixture(int id, int service, long epoch, Probe p, RouterFixture... routers) {
            routing = new ServiceRouting(service, epoch, p, p); p.routing = routing;
            rpc = RpcNode.builder().nodeId(id).bindAddress(LOCAL).handler(routing).build();
            routing.start(rpc); rpc.start();
            for (RouterFixture r : routers) connect(r.id(), r.rpc.localAddress());
        }
        void connect(int router, SocketAddress address) { routing.addRouter(router); rpc.addPeer(router, address, 2); }
        public void close() { routing.close(); rpc.close(); }
    }
    /** A service Node attached to every given Router; its epoch is ten times its ID. */
    static NodeFixture node(int id, int service, Probe p, RouterFixture... routers) throws Exception {
        NodeFixture n = new NodeFixture(id, service, id * 10L, p, routers);
        for (RouterFixture r : routers) await(() -> r.routing.isAttached(id));
        return n;
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }
    static <T> T take(BlockingQueue<T> queue) throws Exception {
        T value = queue.poll(5, TimeUnit.SECONDS); assertNotNull(value); return value;
    }
    static byte[] bytes(RoutedRequest r) { return ByteBufUtil.getBytes(r.request().body()); }

    @Test void callsAndNotifiesKeepIdentityAndEveryRpcField() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(); p2.echo = true;
        Metadata metadata = Metadata.builder().put(MetadataKey.longKey(1), 7L).build();
        try (RouterFixture a = new RouterFixture(101); NodeFixture n1 = node(1, 1, p1, a); NodeFixture n2 = node(2, 2, p2, a)) {
            ByteBuf body = Unpooled.wrappedBuffer(new byte[]{9, 8, 7});
            n1.routing.callNode(2, new RpcRequest(44, 99, 7, 777, metadata, body), v -> {});
            Result response = take(p1.responses);
            assertEquals(List.of(2, 44, 0), List.of(response.target(), response.command(), response.error()));
            assertArrayEquals(new byte[]{9, 8, 7}, response.body()); assertEquals(7L, response.metadata().get(MetadataKey.longKey(1)));
            assertEquals(0, body.refCnt());
            RoutedRequest received = take(p2.requests);
            assertEquals(List.of(1, 10L, 101), List.of(received.sourceNodeId(), received.sourceNodeEpoch(), received.routerId()));
            RpcRequest r = received.request();
            assertEquals(List.of(44, 99L, 7, 777L), List.of(r.command(), r.routeKey(), r.businessIdType(), r.businessId()));
            assertNotEquals(0, r.requestId()); assertEquals(7L, r.metadata().get(MetadataKey.longKey(1)));
            assertEquals(RpcSendStatus.ACCEPTED, n1.routing.notifyNode(2, new RpcRequest(45, Unpooled.wrappedBuffer(new byte[]{1}))));
            RoutedRequest notified = take(p2.requests);
            assertEquals(45, notified.request().command()); assertEquals(0, notified.request().requestId());
            assertEquals(new GameRouter.Stats(3, 0, 0, 0), a.routing.stats());   // request, response, notify
        } finally { p1.release(); p2.release(); }
    }
    @Test void largeBodiesAreRelayedIntactInBothDirections() throws Exception {
        // Large bodies are referenced by the frame being written, not copied: no sender may release them early.
        Probe p1 = new Probe(), p2 = new Probe(); p2.echo = true;
        try (RouterFixture a = new RouterFixture(101); NodeFixture n1 = node(1, 1, p1, a); NodeFixture n2 = node(2, 2, p2, a)) {
            byte[] payload = new byte[64 * 1024]; new Random(3).nextBytes(payload);
            for (int i = 0; i < 20; i++) {
                n1.routing.callNode(2, new RpcRequest(44, Unpooled.wrappedBuffer(payload.clone())), v -> {});
                Result response = take(p1.responses);
                assertEquals(0, response.error()); assertArrayEquals(payload, response.body());
                assertArrayEquals(payload, bytes(take(p2.requests)));
            }
        } finally { p1.release(); p2.release(); }
    }
    @Test void missingTargetsFailCallsAtOnceAndNotifyIsFirstHopAdmissionOnly() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = new RouterFixture(101); NodeFixture n = node(1, 1, p, a)) {
            n.routing.callNode(999, new RpcRequest(9, null), v -> {});
            Result failed = take(p.responses);
            assertEquals(PEER_NOT_FOUND, failed.error()); assertEquals(999, failed.target()); assertTrue(p.failures.isEmpty());
            ByteBuf body = Unpooled.buffer().writeByte(1);
            assertEquals(RpcSendStatus.ACCEPTED, n.routing.notifyNode(999, new RpcRequest(9, body)));
            assertEquals(0, body.refCnt());
            await(() -> a.routing.stats().targetNotFound() == 2);
            ByteBuf invalid = Unpooled.buffer().writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> n.routing.notifyNode(0, new RpcRequest(9, invalid)));
            assertEquals(1, invalid.refCnt()); invalid.release();
        } finally { p.release(); }
    }
    @Test void broadcastReachesEveryAttachedNodeOfTheServiceExactlyOnce() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(), p3 = new Probe(), p4 = new Probe();
        try (RouterFixture a = new RouterFixture(101); RouterFixture b = new RouterFixture(102);
             NodeFixture n1 = node(1, 1, p1, a, b); NodeFixture n2 = node(2, 2, p2, a, b);
             NodeFixture n3 = node(3, 2, p3, a, b); NodeFixture n4 = node(4, 3, p4, a, b)) {
            assertEquals(Set.of(2, 3), a.routing.serviceNodes(2));
            assertEquals(RpcSendStatus.ACCEPTED, n1.routing.broadcast(2, new RpcRequest(55, Unpooled.wrappedBuffer(new byte[]{4}))));
            for (Probe p : List.of(p2, p3)) {
                RoutedRequest r = take(p.requests);
                assertEquals(55, r.request().command()); assertEquals(0, r.request().requestId()); assertArrayEquals(new byte[]{4}, bytes(r));
            }
            // The sender is included when it serves the service.
            assertEquals(RpcSendStatus.ACCEPTED, n2.routing.broadcast(2, new RpcRequest(56, null)));
            assertEquals(56, take(p2.requests).request().command()); assertEquals(56, take(p3.requests).request().command());
            for (Probe p : List.of(p1, p2, p3, p4)) assertNull(p.requests.poll(200, TimeUnit.MILLISECONDS));
        } finally { p1.release(); p2.release(); p3.release(); p4.release(); }
    }
    @Test void oneKeyKeepsOneRouterAndItsOrderWhileKeysSpreadOverRouters() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe();
        try (RouterFixture a = new RouterFixture(101); RouterFixture b = new RouterFixture(102);
             NodeFixture n1 = node(1, 1, p1, a, b); NodeFixture n2 = node(2, 2, p2, a, b)) {
            Thread.sleep(1200);                                 // both Routers usable: the choice no longer changes
            for (int i = 0; i < 1000; i++)
                assertEquals(RpcSendStatus.ACCEPTED, n1.routing.notifyNode(2, new RpcRequest(5, 7, 0, 0, null, Unpooled.buffer(4).writeInt(i))));
            Set<Integer> routers = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                RoutedRequest r = take(p2.requests);
                assertEquals(i, r.request().body().getInt(0)); routers.add(r.routerId());
            }
            assertEquals(1, routers.size());
            p2.release();
            for (long key = 0; key < 64; key++) n1.routing.notifyNode(2, new RpcRequest(6, key, 0, 0, null, null));
            for (int i = 0; i < 64; i++) routers.add(take(p2.requests).routerId());
            assertEquals(Set.of(101, 102), routers);
        } finally { p1.release(); p2.release(); }
    }
    @Test void losingOneRouterMovesTrafficToTheOthers() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(); p2.echo = true;
        try (RouterFixture a = new RouterFixture(101); RouterFixture b = new RouterFixture(102);
             NodeFixture n1 = node(1, 1, p1, a, b); NodeFixture n2 = node(2, 2, p2, a, b)) {
            a.close();
            await(() -> !n1.rpc.isPeerConnected(101) && !n2.rpc.isPeerConnected(101));
            for (long key = 0; key < 32; key++) {
                n1.routing.callNode(2, new RpcRequest(8, key, 0, 0, null, null), v -> {});
                assertEquals(0, take(p1.responses).error());
                assertEquals(102, take(p2.requests).routerId());
            }
        } finally { p1.release(); p2.release(); }
    }
    @Test void callsARouterCouldNotDeliverAreResentThroughAnotherRouter() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(); p2.echo = true;
        try (RouterFixture a = new RouterFixture(101); RouterFixture b = new RouterFixture(102);
             NodeFixture n1 = node(1, 1, p1, a, b); NodeFixture n2 = node(2, 2, p2, a)) {
            // n2 reaches only Router A, so every call that n1 sends through B comes back PEER_NOT_FOUND first.
            for (long key = 0; key < 32; key++) {
                n1.routing.callNode(2, new RpcRequest(8, key, 0, 0, null, null), v -> {});
                assertEquals(0, take(p1.responses).error());
                assertEquals(101, take(p2.requests).routerId());
            }
            assertTrue(b.routing.stats().targetNotFound() > 0);
            assertTrue(p1.failures.isEmpty());
        } finally { p1.release(); p2.release(); }
    }
    @Test void aReconnectedRouterIsUsedOnlyAfterAnAnnouncementPeriod() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe();
        RouterFixture first = new RouterFixture(102);
        SocketAddress address = first.rpc.localAddress();
        try (RouterFixture a = new RouterFixture(101); NodeFixture n1 = node(1, 1, p1, a, first); NodeFixture n2 = node(2, 2, p2, a, first)) {
            first.close();
            await(() -> !n1.rpc.isPeerConnected(102) && !n2.rpc.isPeerConnected(102));
            try (RouterFixture second = new RouterFixture(102, address)) {
                await(() -> second.routing.isAttached(1) && second.routing.isAttached(2));
                // Other Nodes may not have announced themselves on the new Router yet: n1 keeps using A for now.
                for (long key = 0; key < 64; key++) n1.routing.notifyNode(2, new RpcRequest(6, key, 0, 0, null, null));
                Set<Integer> routers = new HashSet<>();
                for (int i = 0; i < 64; i++) routers.add(take(p2.requests).routerId());
                assertEquals(Set.of(101), routers);
                Thread.sleep(1200);
                for (long key = 0; key < 64; key++) n1.routing.notifyNode(2, new RpcRequest(6, key, 0, 0, null, null));
                for (int i = 0; i < 64; i++) routers.add(take(p2.requests).routerId());
                assertEquals(Set.of(101, 102), routers);
            }
        } finally { p1.release(); p2.release(); }
    }
    @Test void aRouterNoLongerInUseIsToldAgainToForgetTheNode() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe();
        try (RouterFixture a = new RouterFixture(101); NodeFixture n1 = node(1, 1, p1, a); NodeFixture n2 = node(2, 2, p2, a)) {
            n2.routing.removeRouter(101);
            await(() -> !a.routing.isAttached(2));
            // A stale announcement (as if it had overtaken the DETACH) makes A deliver to n2 again.
            assertEquals(RpcSendStatus.ACCEPTED, n2.rpc.notify(101, RouterWire.attach(20, 2)));
            await(() -> a.routing.isAttached(2));
            n1.routing.callNode(2, new RpcRequest(9, null), 300, v -> {});
            assertEquals(TIMEOUT, take(p1.failures));
            assertFalse(a.routing.isAttached(2));               // n2 refused the delivery and withdrew again
            assertTrue(p2.requests.isEmpty());
            n1.routing.callNode(2, new RpcRequest(9, null), v -> {});
            assertEquals(PEER_NOT_FOUND, take(p1.responses).error());
        } finally { p1.release(); p2.release(); }
    }
    @Test void aRestartedRouterLearnsItsNodesAgainWithoutAnyRegistration() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(); p2.echo = true;
        RouterFixture first = new RouterFixture(101);
        SocketAddress address = first.rpc.localAddress();
        try (NodeFixture n1 = node(1, 1, p1, first); NodeFixture n2 = node(2, 2, p2, first)) {
            first.close();
            await(() -> !n1.rpc.isPeerConnected(101) && !n2.rpc.isPeerConnected(101));
            try (RouterFixture second = new RouterFixture(101, address)) {
                // n2 only receives: its periodic announcement alone makes it reachable on the new Router.
                await(() -> second.routing.isAttached(1) && second.routing.isAttached(2));
                n1.routing.callNode(2, new RpcRequest(8, null), v -> {});
                assertEquals(0, take(p1.responses).error());
            }
        } finally { p1.release(); p2.release(); }
    }
    @Test void deliveriesAreCheckedAgainstTheTargetsCurrentIncarnation() throws Exception {
        Probe p1 = new Probe(), old = new Probe(), fresh = new Probe();
        try (RouterFixture a = new RouterFixture(101); NodeFixture n1 = node(1, 1, p1, a)) {
            try (NodeFixture n2 = node(2, 2, old, a)) { assertTrue(a.routing.isAttached(2)); }
            try (NodeFixture n2 = new NodeFixture(2, 2, 21, fresh, a)) {
                await(() -> a.routing.isAttached(2));
                // A message for the old incarnation (epoch 20) is refused; the current one (21) is reached.
                BlockingQueue<Integer> stale = new LinkedBlockingQueue<>();
                n1.rpc.call(101, RouterWire.data(RouterWire.DIRECT, 1, 10, 1, 2, 20, 0, new RpcRequest(5, null)),
                        (Integer error) -> stale.add(error));
                assertEquals(PEER_NOT_FOUND, take(stale));
                n1.routing.notifyNode(2, new RpcRequest(6, null));
                assertEquals(6, take(fresh.requests).request().command());
                assertTrue(old.requests.isEmpty());
            }
        } finally { p1.release(); old.release(); fresh.release(); }
    }
    @Test void handlerErrorsReturnAFrameworkFailureWithoutWaitingForTimeout() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(); p2.throwRequest = true;
        try (RouterFixture a = new RouterFixture(101); NodeFixture n1 = node(1, 1, p1, a); NodeFixture n2 = node(2, 2, p2, a)) {
            n1.routing.callNode(2, new RpcRequest(22, null), v -> {});
            Result failed = take(p1.responses);
            assertEquals(HANDLER_ERROR, failed.error()); assertEquals(2, failed.target()); assertTrue(p1.failures.isEmpty());
        } finally { p1.release(); p2.release(); }
    }
    @Test void aMalformedRoutedResponseCompletesTheCallWithAProtocolFailure() throws Exception {
        Probe p = new Probe();
        CompletableFuture<RpcNode> server = new CompletableFuture<>();
        Probe fakeRouter = new Probe() {
            @Override public void onRequest(int source, int slot, RpcRequest r) {
                if (r.requestId() != 0) server.join().reply(source, slot, r.routeKey(), new RpcResponse(r.requestId(), 0, null, Unpooled.wrappedBuffer(new byte[]{99})));
            }
        };
        try (RpcNode rpc = RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(fakeRouter).build();
             NodeFixture n = new NodeFixture(1, 1, 10, p)) {
            server.complete(rpc); rpc.start();
            n.connect(101, rpc.localAddress());
            await(() -> n.rpc.isPeerConnected(101));
            n.routing.callNode(2, new RpcRequest(44, null), ignored -> {});
            assertEquals(PROTOCOL_ERROR, take(p.failures)); assertTrue(p.responses.isEmpty());
            assertTrue(n.rpc.isPeerConnected(101));
        } finally { p.release(); }
    }
    @Test void spoofedSourcesAndUndeclaredRoutersAreRejected() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe();
        try (RouterFixture a = new RouterFixture(101); RouterFixture rogue = new RouterFixture(102);
             NodeFixture n1 = node(1, 1, p1, a); NodeFixture n2 = node(2, 2, p2, a)) {
            // n1 may not claim to be Node 3.
            BlockingQueue<Integer> spoofed = new LinkedBlockingQueue<>();
            n1.rpc.call(101, RouterWire.data(RouterWire.DIRECT, 3, 30, 1, 2, 0, 0, new RpcRequest(5, null)), (Integer error) -> spoofed.add(error));
            assertEquals(HANDLER_ERROR, take(spoofed));
            // n2 is connected to 102 but never declared it as a Router, so 102 cannot deliver to it.
            n2.rpc.addPeer(102, rogue.rpc.localAddress(), 1);
            await(() -> n2.rpc.isPeerConnected(102));
            assertEquals(RpcSendStatus.ACCEPTED, rogue.rpc.notify(2, RouterWire.data(RouterWire.DIRECT, 1, 10, 1, 2, 20, 0, new RpcRequest(7, null))));
            n1.routing.notifyNode(2, new RpcRequest(8, null));
            assertEquals(8, take(p2.requests).request().command());
            assertNull(p2.requests.poll(200, TimeUnit.MILLISECONDS));
        } finally { p1.release(); p2.release(); }
    }
    @Test void ordinaryRpcOnTheSameNodesReachesTheApplicationHandlers() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = new RouterFixture(101); NodeFixture n = node(1, 1, p, a)) {
            assertEquals(RpcSendStatus.ACCEPTED, n.rpc.notify(101, new RpcRequest(77, null)));
            assertEquals(77, take(a.probe.direct));
            assertEquals(RpcSendStatus.ACCEPTED, a.rpc.notify(1, new RpcRequest(78, null)));
            assertEquals(78, take(p.direct));
        } finally { p.release(); }
    }
    @Test void closingWithdrawsTheNodeAndLifecycleMisuseIsRejected() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe();
        try (RouterFixture a = new RouterFixture(101); NodeFixture n1 = node(1, 1, p1, a)) {
            NodeFixture n2 = node(2, 2, p2, a);
            n2.routing.close(); n2.routing.close();
            await(() -> !a.routing.isAttached(2));
            assertTrue(n2.rpc.isPeerConnected(101));           // withdrawn by DETACH, not by losing the connection
            n1.routing.callNode(2, new RpcRequest(9, null), v -> {});
            assertEquals(PEER_NOT_FOUND, take(p1.responses).error());
            assertThrows(IllegalStateException.class, () -> n2.routing.notifyNode(1, new RpcRequest(9, null)));
            assertThrows(IllegalStateException.class, () -> n2.routing.start(n2.rpc));
            n2.rpc.close();

            ServiceRouting unstarted = new ServiceRouting(1, 1, p1, p1);
            assertThrows(IllegalStateException.class, () -> unstarted.addRouter(101));
            assertThrows(IllegalArgumentException.class, () -> new ServiceRouting(0, 1, p1, p1));
            assertThrows(IllegalArgumentException.class, () -> new ServiceRouting(1, 0, p1, p1));
            assertThrows(IllegalArgumentException.class, () -> n1.routing.addRouter(1));
            assertThrows(IllegalArgumentException.class, () -> n1.routing.callNode(2, new RpcRequest(9, 5, 0, 0, 0, null, null), v -> {}));
            assertThrows(IllegalStateException.class, () -> a.routing.start(a.rpc));
            n1.routing.removeRouter(101);
            await(() -> !a.routing.isAttached(1));
            assertThrows(IllegalStateException.class, () -> n1.routing.notifyNode(2, new RpcRequest(9, null)));
        } finally { p1.release(); p2.release(); }
    }
}
