package cn.managame.router.node;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.message.*;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.router.call.*;
import cn.managame.router.route.RouteBinding;
import io.netty.buffer.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static cn.managame.router.error.RouterErrorCodes.*;
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
    static class Probe implements RouterHandler, cn.managame.rpc.call.RpcHandler {
        ServiceRouting client;
        volatile boolean echo;
        volatile boolean throwRequest;
        final BlockingQueue<RoutedRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<Result> responses = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> failures = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> direct = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> registrations = new LinkedBlockingQueue<>();
        final BlockingQueue<DirectCall> directCalls = new LinkedBlockingQueue<>();
        @Override public void onRoutedRequest(RoutedRequest incoming) {
            RpcRequest r = incoming.request();
            // Save independent bytes, never the borrowed Network frame.
            ByteBuf body = r.body() == null ? null : Unpooled.copiedBuffer(r.body());
            requests.add(new RoutedRequest(incoming.sourceNodeId(), incoming.sourceNodeEpoch(),
                    new RpcRequest(r.command(), r.requestId(), r.routeKey(), r.businessIdType(), r.businessId(), r.metadata(), body)));
            if (throwRequest) throw new IllegalStateException("business failure");
            if (echo && r.requestId() != 0) assertEquals(RpcSendStatus.ACCEPTED, client.reply(incoming,
                    new RpcResponse(r.requestId(), 0, r.metadata(), r.body() == null ? null : r.body().retain())));
        }
        @Override public void onRequest(int source, int slot, RpcRequest request) {
            direct.add(request.command()); directCalls.add(new DirectCall(request.requestId(), slot));
        }
        @Override public void onRegistration(int source, int error) { registrations.add(error); }
        @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
            responses.add(new Result(source, command, response.errorCode(), response.body() == null ? new byte[0] : ByteBufUtil.getBytes(response.body())));
            @SuppressWarnings("unchecked") RpcCallback<Integer> completion = (RpcCallback<Integer>) callback;
            completion.onResponse(response.errorCode());
        }
        @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) { failures.add(error); }
        void release() { for (RoutedRequest r : requests) if (r.request().body() != null) r.request().body().release(); requests.clear(); }
    }
    record Result(int source, int command, int error, byte[] body) {}
    record DirectCall(int id, int slot) {}
    // Fixtures alone own the one RpcNode. Production routing extensions do not own/start/close it.
    static final class RouterFixture implements AutoCloseable {
        final cn.managame.rpc.node.RpcNode rpc;
        final GameRouter routing;
        final Probe primary = new Probe();
        RouterFixture(int id) { this(id, LOCAL, id); }
        RouterFixture(int id, java.net.SocketAddress address, long routerEpoch) {
            routing = GameRouter.forRouterNode(routerEpoch, primary);
            rpc = cn.managame.rpc.node.RpcNode.builder().nodeId(id).bindAddress(address).handler(routing).build();
            routing.start(rpc); rpc.start();
        }
        cn.managame.rpc.node.RpcNode rpcNode() { return rpc; }
        void addRouter(int id, java.net.SocketAddress address) { routing.registerRouter(id); rpc.addPeer(id, address, 1); }
        void removeRouter(int id) { rpc.removePeer(id); }
        boolean isRouterReady(int id) { return routing.isRouterReady(id); }
        Optional<RouteBinding> resolve(int service, long key) { return routing.resolve(service, key); }
        Map<cn.managame.router.route.BindingKey, RouteBinding> localBindings() { return routing.localBindings(); }
        Set<Integer> serviceNodes(int service) { return routing.serviceNodes(service); }
        public void close() { routing.close(); rpc.close(); }
    }
    static final class NodeFixture implements AutoCloseable {
        final cn.managame.rpc.node.RpcNode rpc;
        final ServiceRouting routing;
        int home;
        long epoch;
        NodeFixture(int id, int service, Probe p) { this(id, service, p, 5000); }
        NodeFixture(int id, int service, Probe p, long timeoutMillis) {
            routing = GameRouter.forServiceNode(service, p, p);
            rpc = cn.managame.rpc.node.RpcNode.builder().nodeId(id).bindAddress(LOCAL).handler(routing).callTimeout(java.time.Duration.ofMillis(timeoutMillis)).build();
            routing.start(rpc); rpc.start();
        }
        cn.managame.rpc.node.RpcNode rpcNode() { return rpc; }
        Promise<Integer> attach(int id, java.net.SocketAddress address, int slots, long epoch) {
            home = id; this.epoch = epoch;
            Promise<Integer> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
            routing.register(id, epoch, result::trySuccess);
            rpc.addPeer(id, address, slots);
            return result;
        }
        boolean isRegistered() { return routing.isRegistered(); }
        Set<Long> bindings() { return routing.bindings(); }
        Promise<Integer> bind(long key) { Promise<Integer> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE); routing.bind(key, result::trySuccess); return result; }
        Promise<Integer> bind(int service, long key) {
            // Malformed application/control input cannot bypass the Router's registered-service check.
            Promise<Integer> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
            rpc.call(home, RouterWire.packet(RouterWire.control(RouterWire.CLIENT_BIND).writeLong(epoch).writeInt(service).writeLong(key)),
                    (Integer error) -> result.trySuccess(error));
            return result;
        }
        Promise<Integer> unbind(long key) { Promise<Integer> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE); routing.unbind(key, result::trySuccess); return result; }
        Promise<Integer> detach() { Promise<Integer> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE); routing.unregister(result::trySuccess); return result; }
        void detachAfterLoss() { routing.unregisterAfterLoss(); }
        void callNode(int id, RpcRequest r, RpcCallback<Object> callback) { routing.callNode(id, r, callback); }
        void callBinding(int service, long key, RpcRequest r, RpcCallback<Object> callback) { routing.callBinding(service, key, r, callback); }
        RpcSendStatus notifyBinding(int service, long key, RpcRequest r) { return routing.notifyBinding(service, key, r); }
        RpcSendStatus broadcast(int service, RpcRequest r) { return routing.broadcast(service, r); }
        RpcSendStatus reply(RoutedRequest r, RpcResponse response) { return routing.reply(r, response); }
        public void close() { routing.close(); rpc.close(); }
    }
    static RouterFixture router(int id) { return new RouterFixture(id); }
    static NodeFixture client(int id, int service, Probe p, RouterFixture r) throws Exception {
        NodeFixture c = new NodeFixture(id, service, p); p.client = c.routing;
        c.attach(r.rpcNode().nodeId(), r.rpcNode().localAddress(), 3, id).get(5, TimeUnit.SECONDS);
        return c;
    }
    static void connect(RouterFixture a, RouterFixture b) throws Exception {
        b.routing.registerRouter(a.rpc.nodeId());
        a.addRouter(b.rpcNode().nodeId(), b.rpcNode().localAddress());
        await(() -> a.isRouterReady(b.rpcNode().nodeId()) && b.isRouterReady(a.rpcNode().nodeId()));
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }
    static <T> T take(BlockingQueue<T> queue) throws Exception {
        T value = queue.poll(5, TimeUnit.SECONDS); assertNotNull(value); return value;
    }
    @Test void dynamicAndPhysicalCallsAcrossRoutersPreserveIdentityAndRpcFields() throws Exception {
        Probe pa = new Probe(), pb = new Probe(); pb.echo = true;
        try (RouterFixture a = router(101); RouterFixture b = router(102);
             NodeFixture ca = client(1, 1, pa, a); NodeFixture cb = client(2, 2, pb, b)) {
            connect(a, b); assertEquals(0, cb.bind(900).get(5, TimeUnit.SECONDS));
            await(() -> a.resolve(2, 900).isPresent());
            ByteBuf body = Unpooled.wrappedBuffer(new byte[]{9, 8, 7});
            ca.callBinding(2, 900, new RpcRequest(44, 99, 7, 777, null, body), v -> {});
            Result response = take(pa.responses);
            assertEquals(2, response.source()); assertEquals(44, response.command());
            assertEquals(0, response.error()); assertArrayEquals(new byte[]{9, 8, 7}, response.body()); assertEquals(0, body.refCnt());
            RoutedRequest received = take(pb.requests);
            assertEquals(1, received.sourceNodeId()); assertEquals(1, received.sourceNodeEpoch());
            assertEquals(99, received.request().routeKey()); assertEquals(7, received.request().businessIdType());
            assertEquals(777, received.request().businessId()); assertNotEquals(0, received.request().requestId());
            received.request().body().release();
            ca.callNode(2, new RpcRequest(45, null), v -> {});
            assertEquals(45, take(pa.responses).command());
            // Unrelated direct Peers still use the ordinary RPC handler on this same RpcNode.
            ca.rpcNode().addPeer(2, cb.rpcNode().localAddress(), 1);
            await(() -> ca.rpcNode().notify(2, new RpcRequest(77, null)) == RpcSendStatus.ACCEPTED);
            assertEquals(77, take(pb.direct));
        } finally { pa.release(); pb.release(); }
    }
    @Test void responseReturnsToOriginalNodeAfterBindingWasRemoved() throws Exception {
        Probe pa = new Probe(), pb = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102);
             NodeFixture ca = client(1, 1, pa, a); NodeFixture cb = client(2, 2, pb, b)) {
            connect(a, b); cb.bind(88).get(5, TimeUnit.SECONDS); await(() -> a.resolve(2, 88).isPresent());
            ca.callBinding(2, 88, new RpcRequest(12, null), v -> {});
            RoutedRequest incoming = take(pb.requests);
            cb.unbind(88).get(5, TimeUnit.SECONDS); await(() -> a.resolve(2, 88).isEmpty());
            cb.reply(incoming, new RpcResponse(incoming.request().requestId(), 41, null, Unpooled.wrappedBuffer(new byte[]{3})));
            incoming.request().body().release();
            Result reply = take(pa.responses); assertEquals(41, reply.error()); assertEquals(2, reply.source());
            assertArrayEquals(new byte[]{3}, reply.body());
        } finally { pa.release(); pb.release(); }
    }
    @Test void missingRouteCallsFailImmediatelyAndNotifyHasOnlyFirstHopAcceptance() throws Exception {
        Probe p = new Probe();
        try (RouterFixture r = router(100); NodeFixture c = client(1, 1, p, r)) {
            c.callBinding(2, 7, new RpcRequest(9, null), v -> {});
            assertEquals(ROUTE_NOT_FOUND, take(p.responses).error()); assertTrue(p.failures.isEmpty());
            c.callNode(999, new RpcRequest(9, null), v -> {});
            assertEquals(PEER_NOT_FOUND, take(p.responses).error());
            ByteBuf body = Unpooled.buffer().writeByte(1);
            assertEquals(RpcSendStatus.ACCEPTED, c.notifyBinding(2, 7, new RpcRequest(9, body)));
            assertEquals(0, body.refCnt()); assertTrue(p.requests.isEmpty());
            ByteBuf invalid = Unpooled.buffer().writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> c.routing.notifyNode(0, new RpcRequest(9, invalid)));
            assertEquals(1, invalid.refCnt()); invalid.release();
        } finally { p.release(); }
    }
    @Test void broadcastFansOutOncePerMatchingLocalNodeWithoutRouterLoops() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(), p3 = new Probe(), p4 = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); RouterFixture c = router(103);
             NodeFixture n1 = client(1, 1, p1, a); NodeFixture n2 = client(2, 1, p2, b);
             NodeFixture n3 = client(3, 1, p3, c); NodeFixture n4 = client(4, 2, p4, c)) {
            connect(a, b); connect(a, c); connect(b, c);
            await(() -> a.serviceNodes(1).equals(Set.of(1, 2, 3)));
            assertEquals(RpcSendStatus.ACCEPTED, n1.broadcast(1, new RpcRequest(55, Unpooled.wrappedBuffer(new byte[]{4}))));
            for (Probe p : List.of(p1, p2, p3)) {
                RoutedRequest r = take(p.requests); assertEquals(55, r.request().command()); assertEquals(0, r.request().requestId());
                r.request().body().release();
            }
            assertNull(p4.requests.poll(150, TimeUnit.MILLISECONDS));
            assertTrue(p1.requests.isEmpty()); assertTrue(p2.requests.isEmpty()); assertTrue(p3.requests.isEmpty());
        } finally { p1.release(); p2.release(); p3.release(); p4.release(); }
    }
    @Test void snapshotIncludesExistingNodesAndBindingsThenOrderedDeltas() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture c = client(1, 5, p, a)) {
            for (int i = 0; i < 1500; i++) assertEquals(0, c.bind(i).get(5, TimeUnit.SECONDS));
            b.routing.registerRouter(101);
            a.addRouter(102, b.rpcNode().localAddress());
            for (int i = 1500; i < 1600; i++) assertEquals(0, c.bind(i).get(5, TimeUnit.SECONDS));
            await(() -> b.isRouterReady(101) && b.resolve(5, 1599).isPresent());
            for (int i = 0; i < 1600; i++) assertEquals(new RouteBinding(1, 1), b.resolve(5, i).orElseThrow());
            assertEquals(Set.of(1), b.serviceNodes(5));
            a.removeRouter(102); await(() -> !b.isRouterReady(101));
            assertEquals(new RouteBinding(1, 1), b.resolve(5, 1).orElseThrow());
            assertEquals(0, c.bind(1600).get(5, TimeUnit.SECONDS));
            assertEquals(new RouteBinding(1, 1), b.resolve(5, 1).orElseThrow());
            connect(a, b); await(() -> b.resolve(5, 1599).isPresent());
            await(() -> b.resolve(5, 1600).isPresent());
        } finally { p.release(); }
    }
    @Test void localConflictServiceValidationAndDeterministicPartitionConflict() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102);
             NodeFixture n1 = client(1, 1, p1, a); NodeFixture n2 = client(2, 1, p2, b)) {
            assertEquals(INVALID_SERVICE, n1.bind(2, 7).get(5, TimeUnit.SECONDS));
            assertEquals(0, n1.bind(7).get(5, TimeUnit.SECONDS)); assertEquals(0, n1.bind(7).get(5, TimeUnit.SECONDS));
            assertEquals(0, n2.bind(7).get(5, TimeUnit.SECONDS)); connect(a, b);
            await(() -> a.resolve(1, 7).orElseThrow().nodeId() == 1 && b.resolve(1, 7).orElseThrow().nodeId() == 1);
            assertEquals(BINDING_CONFLICT, n2.bind(7).get(5, TimeUnit.SECONDS));
            assertEquals(0, n2.unbind(7).get(5, TimeUnit.SECONDS));
            await(() -> b.resolve(1, 7).orElseThrow().nodeId() == 1);
        } finally { p1.release(); p2.release(); }
    }
    @Test void transportLossRetainsNodeRoutesAndDiscoveryOwnsOfflineRemoval() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture n = client(1, 1, p, a)) {
            connect(a, b); n.bind(7).get(5, TimeUnit.SECONDS); n.bind(8).get(5, TimeUnit.SECONDS);
            await(() -> b.resolve(1, 8).isPresent());
            n.rpcNode().removePeer(101);
            await(() -> !n.isRegistered());
            assertEquals(new RouteBinding(1, 1), a.resolve(1, 8).orElseThrow());
            assertEquals(new RouteBinding(1, 1), b.resolve(1, 8).orElseThrow());
            n.rpcNode().addPeer(101, a.rpcNode().localAddress(), 3);
            await(() -> n.isRegistered() && b.resolve(1, 7).isPresent() && b.resolve(1, 8).isPresent());
            assertEquals(Set.of(7L, 8L), n.bindings());
            n.close();
            a.routing.removeNode(1, 999); // Late/wrong discovery event cannot remove this incarnation.
            assertEquals(new RouteBinding(1, 1), a.resolve(1, 8).orElseThrow());
            a.routing.removeNode(1, 1);
            await(() -> a.localBindings().isEmpty() && b.resolve(1, 8).isEmpty());
        } finally { p.release(); }
    }
    @Test void routerCrashAllowsExplicitFailoverAndNormalDetachRemovesBeforeRebind() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture n = client(1, 1, p, a)) {
            n.bind(7).get(5, TimeUnit.SECONDS); a.close(); await(() -> !n.isRegistered());
            n.detachAfterLoss(); n.attach(102, b.rpcNode().localAddress(), 2, 2).get(5, TimeUnit.SECONDS);
            assertEquals(new RouteBinding(1, 2), b.resolve(1, 7).orElseThrow());
            assertEquals(0, n.detach().get(5, TimeUnit.SECONDS)); assertTrue(b.localBindings().isEmpty());
            assertTrue(n.bindings().isEmpty());
            assertTrue(n.rpc.isPeerConnected(102));
        } finally { p.release(); }
    }
    @Test void handlerErrorReturnsFrameworkFailureWithoutWaitingForTimeout() throws Exception {
        Probe p1 = new Probe(), p2 = new Probe(); p2.throwRequest = true;
        try (RouterFixture a = router(101); RouterFixture b = router(102);
             NodeFixture n1 = client(1, 1, p1, a); NodeFixture n2 = client(2, 2, p2, b)) {
            connect(a, b); n1.callNode(2, new RpcRequest(22, null), v -> {});
            assertEquals(HANDLER_ERROR, take(p1.responses).error()); assertTrue(p1.failures.isEmpty());
        } finally { p1.release(); p2.release(); }
    }
    @Test void malformedMatchedRouterResponseCompletesWithProtocolFailure() throws Exception {
        Probe p = new Probe();
        java.util.concurrent.atomic.AtomicReference<cn.managame.rpc.node.RpcNode> server = new java.util.concurrent.atomic.AtomicReference<>();
        Probe malformed = new Probe() {
            @Override public void onRequest(int source, int slot, RpcRequest r) {
                ByteBuf b = r.body().duplicate();
                int op = RouterWire.operation(b);
                server.get().reply(source, slot, r.routeKey(), new RpcResponse(r.requestId(), 0, null,
                        op == RouterWire.DATA ? Unpooled.wrappedBuffer(new byte[]{99}) :
                                (op == RouterWire.REGISTER || op == RouterWire.VERIFY ? Unpooled.buffer(8).writeLong(101) : null)));
            }
        };
        try (var rpc = cn.managame.rpc.node.RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(malformed).build();
             NodeFixture n = new NodeFixture(1, 1, p)) {
            server.set(rpc); rpc.start(); n.attach(101, rpc.localAddress(), 1, 1).get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, n::detachAfterLoss);
            n.callNode(2, new RpcRequest(44, null), ignored -> {});
            assertEquals(PROTOCOL_ERROR, take(p.failures)); assertTrue(p.responses.isEmpty());
            assertTrue(n.rpc.isPeerConnected(101));
        } finally { p.release(); }
    }
    @Test void routingDeclarationsLeaveConnectionConfigurationAndDetachWithRpc() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture n = new NodeFixture(1, 1, p)) {
            a.routing.registerRouter(102); b.routing.registerRouter(101);
            assertEquals(0, a.rpc.peerSlotCount(102)); assertFalse(a.isRouterReady(102));
            a.rpc.addPeer(102, b.rpc.localAddress(), 1);
            await(() -> a.isRouterReady(102) && b.isRouterReady(101));
            Promise<Integer> registration = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
            n.routing.register(101, 1, registration::trySuccess);
            assertFalse(registration.isDone()); assertEquals(0, n.rpc.peerSlotCount(101));
            n.rpc.addPeer(101, a.rpc.localAddress(), 2);
            registration.get(5, TimeUnit.SECONDS);
            n.bind(7).get(5, TimeUnit.SECONDS);
            await(() -> b.resolve(1, 7).isPresent());
            assertEquals(0, n.detach().get(5, TimeUnit.SECONDS));
            assertTrue(n.rpc.isPeerConnected(101)); assertEquals(2, n.rpc.peerSlotCount(101));
            assertEquals(RpcSendStatus.ACCEPTED, n.rpc.notify(101, new RpcRequest(77, null)));
            assertEquals(77, take(a.primary.direct));
            // Retiring routing membership neither removes nor reconfigures the shared RPC Peer.
            a.routing.unregisterRouter(102);
            assertFalse(a.isRouterReady(102)); assertTrue(a.rpc.isPeerConnected(102));
        } finally { p.release(); }
    }
    @Test void existingRpcConnectionsCanAcquireRoutingRolesAndRejectMultipleRouterSlots() throws Exception {
        try (RouterFixture a = router(101); RouterFixture b = router(102)) {
            a.rpc.addPeer(102, b.rpc.localAddress(), 1);
            await(() -> a.rpc.isPeerConnected(102) && b.rpc.isPeerConnected(101));
            assertFalse(a.isRouterReady(102));
            a.routing.registerRouter(102); b.routing.registerRouter(101);
            await(() -> a.isRouterReady(102) && b.isRouterReady(101));
        }
        try (RouterFixture a = router(101); RouterFixture b = router(102)) {
            a.rpc.addPeer(102, b.rpc.localAddress(), 2);
            await(() -> a.rpc.isPeerConnected(102));
            assertThrows(IllegalStateException.class, () -> a.routing.registerRouter(102));
            assertEquals(2, a.rpc.peerSlotCount(102)); assertFalse(a.isRouterReady(102));
        }
    }
    @Test void invalidSynchronizationRestoresSnapshotsWithoutDisconnectingOrdinaryRpcCalls() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture n = client(1, 1, p, a)) {
            n.bind(7).get(5, TimeUnit.SECONDS); connect(a, b);
            await(() -> b.resolve(1, 7).isPresent());
            BlockingQueue<Integer> ready = new LinkedBlockingQueue<>();
            // Capture an ordinary in-flight request: synchronization recovery must preserve its RPC lifetime.
            a.rpc.call(102, new RpcRequest(77, null), (Integer error) -> ready.add(error));
            assertEquals(77, take(b.primary.direct));
            DirectCall pending = take(b.primary.directCalls);
            BlockingQueue<Integer> malformed = new LinkedBlockingQueue<>();
            a.rpc.call(102, RouterWire.packet(RouterWire.control(RouterWire.BEGIN).writeLong(999).writeInt(0).writeInt(0)),
                    (Integer error) -> malformed.add(error));
            assertEquals(HANDLER_ERROR, take(malformed));
            await(() -> a.isRouterReady(102) && b.isRouterReady(101) && b.resolve(1, 7).isPresent());
            assertEquals(1, a.rpc.peerSlotCount(102));
            assertTrue(a.primary.failures.isEmpty());
            assertTrue(a.rpc.isPeerConnected(102)); assertTrue(b.rpc.isPeerConnected(101));
            n.bind(8).get(5, TimeUnit.SECONDS); await(() -> b.resolve(1, 8).isPresent());
            assertEquals(RpcSendStatus.ACCEPTED, b.rpc.reply(101, pending.slot(), 0, new RpcResponse(pending.id(), 0, null, null)));
            assertEquals(0, take(ready)); assertTrue(a.primary.failures.isEmpty());
        } finally { p.release(); }
    }
    @Test void callbacksCompleteOnceAndExceptionsCannotStallQueuedControls() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); NodeFixture n = client(1, 1, p, a)) {
            assertEquals(0, take(p.registrations));
            BlockingQueue<Integer> completed = new LinkedBlockingQueue<>();
            n.routing.bind(7, error -> { completed.add(error); throw new IllegalStateException("callback failure"); });
            n.routing.bind(8, completed::add);
            assertEquals(0, take(completed)); assertEquals(0, take(completed));
            assertEquals(Set.of(7L, 8L), n.bindings());
            n.rpc.removePeer(101);
            await(() -> !n.isRegistered());
            n.rpc.addPeer(101, a.rpc.localAddress(), 3);
            await(n::isRegistered);
            // A brief reconnect may preserve verified protocol state without another registration event.
            assertEquals(Set.of(7L, 8L), n.bindings());
            assertTrue(completed.isEmpty());
        } finally { p.release(); }
    }
    @Test void closingOrClearingAnUnconnectedRegistrationCompletesItsCallbackOnce() throws Exception {
        Probe p = new Probe();
        NodeFixture n = new NodeFixture(1, 1, p);
        BlockingQueue<Integer> completed = new LinkedBlockingQueue<>();
        n.routing.register(101, 1, completed::add);
        n.close(); assertEquals(NODE_CLOSED, take(completed)); assertTrue(completed.isEmpty());
        try (NodeFixture replacement = new NodeFixture(2, 1, p)) {
            replacement.routing.register(101, 2, completed::add);
            replacement.routing.unregisterAfterLoss();
            assertEquals(PEER_REMOVED, take(completed)); assertTrue(completed.isEmpty());
        }
        assertTrue(completed.isEmpty());
    }
}
