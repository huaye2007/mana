package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.router.call.RouterHandler;
import cn.managame.router.call.RoutedRequest;
import cn.managame.router.route.RouteBinding;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.managame.router.node.RouterIntegrationTest.*;
import static cn.managame.router.node.RouterWire.*;
import static cn.managame.router.error.RouterErrorCodes.*;
import static cn.managame.rpc.error.RpcErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;

class RouterRecoveryTest {
    @BeforeAll static void setup() throws Exception { environment(); }

    @Test void assemblyStartsOnceAndClosedRolesRejectStateMutationWithoutOwningRpc() {
        Probe p = new Probe();
        try (GameRouter r = GameRouter.forRouterNode(1, p);
             RpcNode rpc = RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(r).build()) {
            assertThrows(IllegalStateException.class, () -> r.removeNode(1, 1));
            r.start(rpc); assertThrows(IllegalStateException.class, () -> r.start(rpc));
            rpc.start(); r.close();
            assertThrows(IllegalStateException.class, () -> r.registerRouter(102));
            assertThrows(IllegalStateException.class, () -> r.unregisterRouter(102));
            assertThrows(IllegalStateException.class, () -> r.removeNode(1, 1));
            assertNotNull(rpc.localAddress());
            rpc.addPeer(102, new java.net.InetSocketAddress("127.0.0.1", 1), 1);
            assertEquals(1, rpc.peerSlotCount(102));
        }
    }

    private static Object field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }
    private static void maintain(Object routing) throws Exception {
        Method method = routing.getClass().getDeclaredMethod("maintain");
        method.setAccessible(true); method.invoke(routing);
    }

    /** Drops Router stream frames from one peer, so this Router never holds that peer's replica. */
    private static RpcHandler withoutStreamFrom(int peer, RpcHandler router) {
        return new RpcHandler() {
            @Override public void onRequest(int source, int slot, RpcRequest request) {
                if (source == peer && request.command() == COMMAND) {
                    int op = operation(request.body().duplicate());
                    if (op >= BEGIN && op <= STATE) return; // SYNC requests still pass: B streams to A
                }
                router.onRequest(source, slot, request);
            }
            @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
                router.onResponse(source, command, response, callback);
            }
            @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
                router.onFail(target, command, error, callback);
            }
        };
    }

    @Test void responsesAndRoutedErrorsReturnToTheOriginRouterWithoutItsReplica() throws Exception {
        Probe sourceProbe = new Probe(), targetProbe = new Probe(); targetProbe.echo = true;
        GameRouter b = GameRouter.forRouterNode(102, new Probe());
        try (RouterFixture a = router(101); b;
             RpcNode rpcB = RpcNode.builder().nodeId(102).bindAddress(LOCAL).handler(withoutStreamFrom(101, b)).build();
             NodeFixture source = new NodeFixture(1, 1, sourceProbe); NodeFixture target = new NodeFixture(2, 2, targetProbe)) {
            b.start(rpcB); rpcB.start();
            sourceProbe.client = source.routing; targetProbe.client = target.routing;
            assertEquals(0, source.attach(101, a.rpc.localAddress(), 1, 1).get(5, TimeUnit.SECONDS));
            assertEquals(0, target.attach(102, rpcB.localAddress(), 1, 2).get(5, TimeUnit.SECONDS));
            b.registerRouter(101); a.addRouter(102, rpcB.localAddress());
            await(() -> a.isRouterReady(102));
            assertFalse(b.isRouterReady(101)); assertTrue(b.serviceNodes(1).isEmpty()); // B never learns A's bucket
            source.callNode(2, new RpcRequest(810, Unpooled.wrappedBuffer(new byte[]{5})), ignored -> {});
            Result echoed = take(sourceProbe.responses);
            assertEquals(810, echoed.command()); assertEquals(0, echoed.error()); assertArrayEquals(new byte[]{5}, echoed.body());
            RoutedRequest delivered = take(targetProbe.requests);
            assertEquals(101, delivered.originRouterId()); delivered.request().body().release();
            target.rpc.removePeer(102); await(() -> !rpcB.isPeerConnected(2));
            source.routing.callNode(2, new RpcRequest(811, null), 1500, ignored -> {});
            Result rejected = take(sourceProbe.responses);
            assertEquals(811, rejected.command()); assertEquals(PEER_NOT_FOUND, rejected.error());
            assertTrue(sourceProbe.failures.isEmpty());
        } finally { sourceProbe.release(); targetProbe.release(); }
    }

    @Test void routerSlotReconfigurationStopsSynchronizationButKeepsReplicas() throws Exception {
        Probe pa = new Probe(), pb = new Probe(); pb.echo = true;
        try (RouterFixture a = router(101); RouterFixture b = router(102);
             NodeFixture source = new NodeFixture(1, 1, pa); NodeFixture target = new NodeFixture(2, 2, pb)) {
            pa.client = source.routing; pb.client = target.routing;
            assertEquals(0, source.attach(101, a.rpc.localAddress(), 1, 1).get(5, TimeUnit.SECONDS));
            assertEquals(0, target.attach(102, b.rpc.localAddress(), 1, 2).get(5, TimeUnit.SECONDS));
            assertEquals(0, target.bind(42).get(5, TimeUnit.SECONDS)); connect(a, b);
            await(() -> a.resolve(2, 42).isPresent());
            b.rpc.removePeer(101); a.rpc.removePeer(102); a.rpc.addPeer(102, b.rpc.localAddress(), 2);
            await(() -> a.rpc.isPeerConnected(102) && b.rpc.isPeerConnected(101));
            assertFalse(a.isRouterReady(102)); assertFalse(b.isRouterReady(101));
            assertEquals(Optional.of(new RouteBinding(2, 2)), a.resolve(2, 42)); // retained replica stays queryable
            assertEquals(0, target.bind(43).get(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertTrue(a.resolve(2, 43).isEmpty()); // no synchronization over a multi-Slot Router Peer
            b.rpc.removePeer(101); a.rpc.removePeer(102); a.rpc.addPeer(102, b.rpc.localAddress(), 1);
            await(() -> a.isRouterReady(102) && b.isRouterReady(101) && a.resolve(2, 43).isPresent());
            source.callBinding(2, 43, new RpcRequest(814, null), ignored -> {});
            assertEquals(0, take(pa.responses).error());
            var received = take(pb.requests);
            assertEquals(814, received.request().command());
        } finally { pa.release(); pb.release(); }
    }

    private static Object link(RouterFixture r, int peer) throws Exception {
        return ((Map<?, ?>) field(field(r.routing, "engine"), "links")).get(peer);
    }
    private static long inSession(RouterFixture r, int peer) {
        try {
            Object l = link(r, peer);
            synchronized (field(l, "replicaLock")) { return (long) field(l, "inSession"); }
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void streamGapKeepsReplicaQueryableAndResynchronizes() throws Exception {
        Probe pa = new Probe(), pb = new Probe(); pb.echo = true;
        try (RouterFixture a = router(101); RouterFixture b = router(102);
             NodeFixture na = new NodeFixture(1, 1, pa); NodeFixture nb = new NodeFixture(2, 1, pb)) {
            pa.client = na.routing; pb.client = nb.routing;
            assertEquals(0, na.attach(101, a.rpc.localAddress(), 1, 1).get(5, TimeUnit.SECONDS));
            assertEquals(0, nb.attach(102, b.rpc.localAddress(), 1, 2).get(5, TimeUnit.SECONDS));
            assertEquals(0, na.bind(42).get(5, TimeUnit.SECONDS)); connect(a, b);
            await(() -> b.resolve(1, 42).isPresent());
            long before = inSession(b, 101);
            // A frame of an unknown later session means B missed a BEGIN: it must resynchronize.
            assertEquals(cn.managame.rpc.transport.RpcSendStatus.ACCEPTED, a.rpc.notify(102,
                    packet(sync(DELTA, 101, Long.MAX_VALUE, 1, 0).writeShort(0))));
            await(() -> inSession(b, 101) != before && b.isRouterReady(101));
            assertEquals(Optional.of(new RouteBinding(1, 1)), b.resolve(1, 42));
            assertEquals(0, na.bind(43).get(5, TimeUnit.SECONDS));
            await(() -> b.resolve(1, 43).isPresent());
            nb.callBinding(1, 43, new RpcRequest(805, null), ignored -> {});
            assertEquals(805, take(pa.requests).request().command());
        } finally { pa.release(); pb.release(); }
    }

    private record Verification(int source, int slot, RpcRequest request) {}

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void nontransientVerificationFailureStopsRetriesAndPreservesExplicitRetry(int failureKind) throws Exception {
        Probe p = new Probe(); AtomicReference<RpcNode> server = new AtomicReference<>();
        AtomicInteger registers = new AtomicInteger(), binds = new AtomicInteger();
        AtomicBoolean holdVerification = new AtomicBoolean(true);
        BlockingQueue<Verification> verification = new LinkedBlockingQueue<>();
        Probe remote = new Probe() {
            public void onRequest(int source, int slot, RpcRequest request) {
                int op = operation(request.body().duplicate());
                if (op == REGISTER) registers.incrementAndGet();
                if (op == CLIENT_BIND) binds.incrementAndGet();
                if (op == VERIFY && holdVerification.get()) {
                    // Retain only immutable fields; the inbound body is borrowed.
                    verification.add(new Verification(source, slot, new RpcRequest(request.command(),
                            request.requestId(), request.routeKey(), 0, 0, null, null))); return;
                }
                server.get().reply(source, slot, request.routeKey(), new RpcResponse(request.requestId(), 0, null,
                        op == REGISTER || op == VERIFY ? Unpooled.buffer(8).writeLong(303) : null));
            }
        };
        try (RpcNode r = RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(remote).build();
             NodeFixture n = new NodeFixture(1, 1, p)) {
            server.set(r); r.start(); assertEquals(0, n.attach(101, r.localAddress(), 1, 7).get(5, TimeUnit.SECONDS));
            assertEquals(0, take(p.registrations)); assertEquals(0, n.bind(42).get(5, TimeUnit.SECONDS));
            Channel original = channel(n.rpc, 101, 0);
            Verification v = take(verification);
            BlockingQueue<Integer> abandoned = new LinkedBlockingQueue<>();
            n.routing.bind(43, abandoned::add); n.routing.unbind(42, abandoned::add);
            int expectedError = failureKind == 4 ? INVALID_SERVICE : PROTOCOL_ERROR;
            var malformed = switch (failureKind) {
                case 1 -> Unpooled.buffer(4).writeInt(303);
                case 2 -> Unpooled.buffer(8).writeLong(0);
                case 3 -> Unpooled.buffer(9).writeLong(303).writeByte(0);
                default -> null;
            };
            assertEquals(cn.managame.rpc.transport.RpcSendStatus.ACCEPTED, r.reply(v.source, v.slot, v.request.routeKey(),
                    new RpcResponse(v.request.requestId(), failureKind == 4 ? INVALID_SERVICE : 0, null, malformed)));
            assertEquals(expectedError, take(abandoned)); assertEquals(expectedError, take(abandoned));
            assertEquals(expectedError, take(p.registrations));
            assertFalse(n.isRegistered()); assertEquals(Set.of(42L), n.bindings());
            assertSame(original, channel(n.rpc, 101, 0));
            for (int tick = 0; tick < 10; tick++) maintain(n.routing);
            assertEquals(1, registers.get()); assertTrue(verification.isEmpty()); assertTrue(abandoned.isEmpty());
            // Even an observed connection loss must not revive a stopped protocol selection.
            n.rpc.removePeer(101); maintain(n.routing);
            n.rpc.addPeer(101, r.localAddress(), 1); await(() -> n.rpc.isPeerConnected(101));
            for (int tick = 0; tick < 10; tick++) maintain(n.routing);
            assertFalse(n.isRegistered()); assertEquals(1, registers.get()); assertTrue(p.registrations.isEmpty());
            holdVerification.set(false);
            BlockingQueue<Integer> retried = new LinkedBlockingQueue<>();
            n.routing.register(101, 7, retried::add);
            assertEquals(0, take(retried)); assertEquals(0, take(p.registrations));
            assertTrue(n.isRegistered()); assertEquals(Set.of(42L), n.bindings());
            assertEquals(2, registers.get()); assertEquals(2, binds.get());
            assertTrue(retried.isEmpty()); assertTrue(abandoned.isEmpty());
        } finally { p.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {TIMEOUT, NOT_REGISTERED})
    void recoverableVerificationFailureStillRestoresBindingsWithoutResettingPeer(int error) throws Exception {
        Probe p = new Probe(); AtomicReference<RpcNode> server = new AtomicReference<>();
        AtomicInteger registers = new AtomicInteger(), binds = new AtomicInteger(), verifies = new AtomicInteger();
        Probe remote = new Probe() {
            public void onRequest(int source, int slot, RpcRequest request) {
                int op = operation(request.body().duplicate());
                if (op == REGISTER) registers.incrementAndGet();
                if (op == CLIENT_BIND) binds.incrementAndGet();
                if (op == VERIFY && verifies.incrementAndGet() == 1) {
                    if (error != TIMEOUT) server.get().reply(source, slot, request.routeKey(),
                            new RpcResponse(request.requestId(), error, null, null));
                    return;
                }
                server.get().reply(source, slot, request.routeKey(), new RpcResponse(request.requestId(), 0, null,
                        op == REGISTER || op == VERIFY ? Unpooled.buffer(8).writeLong(303) : null));
            }
        };
        try (RpcNode r = RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(remote).build();
             NodeFixture n = new NodeFixture(1, 1, p, 150)) {
            server.set(r); r.start(); assertEquals(0, n.attach(101, r.localAddress(), 1, 7).get(5, TimeUnit.SECONDS));
            assertEquals(0, take(p.registrations)); assertEquals(0, n.bind(42).get(5, TimeUnit.SECONDS));
            Channel original = channel(n.rpc, 101, 0);
            assertEquals(0, take(p.registrations));
            assertTrue(n.isRegistered()); assertEquals(Set.of(42L), n.bindings());
            assertEquals(2, registers.get()); assertEquals(2, binds.get());
            assertSame(original, channel(n.rpc, 101, 0));
        } finally { p.release(); }
    }
    private static Channel channel(RpcNode rpc, int peerId, int slot) throws Exception {
        Object peer = ((Map<?, ?>) field(rpc, "peers")).get(peerId);
        Object connection = ((AtomicReference<?>) field(Array.get(field(peer, "slots"), slot), "connection")).get();
        return connection == null ? null : (Channel) field(connection, "channel");
    }
    private static boolean newSlot(RpcNode rpc, Channel old) {
        try { Channel current = channel(rpc, 101, 0); return current != null && current != old && current.isActive(); }
        catch (Exception missing) { return false; }
    }

    @Test void routerRestartRecoversEvenWhenOneOldSlotStillAppearsActive() throws Exception {
        Probe p = new Probe(); RouterFixture old = router(101);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (NodeFixture n = new NodeFixture(1, 1, p, 250)) {
            assertEquals(0, n.attach(101, old.rpc.localAddress(), 2, 7).get(5, TimeUnit.SECONDS));
            assertEquals(0, n.bind(42).get(5, TimeUnit.SECONDS));
            await(() -> { try { return channel(n.rpc, 101, 1) != null; } catch (Exception e) { return false; } });
            Channel fast = channel(n.rpc, 101, 0), stale = channel(n.rpc, 101, 1);
            assertNotSame(fast.eventLoop(), stale.eventLoop());
            stale.eventLoop().execute(() -> {
                blocked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release missing"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            SocketAddress address = old.rpc.localAddress(); old.close();
            try (RouterFixture replacement = new RouterFixture(101, address, 202)) {
                await(() -> newSlot(n.rpc, fast));
                assertTrue(stale.isActive(), "old Slot must still look active during new connection admission");
                release.countDown();
                await(() -> n.isRegistered() && replacement.resolve(1, 42).equals(Optional.of(new RouteBinding(1, 7))));
                assertEquals(Set.of(42L), n.bindings());
            }
        } finally { release.countDown(); old.close(); p.release(); }
    }

    @Test void lostRegistrationAckRetriesWithoutRecreatingPeerAndCompletesInitialCallbackOnce() throws Exception {
        Probe p = new Probe(); AtomicInteger attempts = new AtomicInteger(), callbacks = new AtomicInteger();
        AtomicReference<RpcNode> server = new AtomicReference<>();
        Probe remote = new Probe() {
            @Override public void onRequest(int source, int slot, RpcRequest request) {
                int op = operation(request.body().duplicate());
                if (op == REGISTER && attempts.incrementAndGet() == 1) return; // accepted remotely, ACK lost
                server.get().reply(source, slot, request.routeKey(), new RpcResponse(request.requestId(), 0, null,
                        op == REGISTER || op == VERIFY ? Unpooled.buffer(8).writeLong(303) : null));
            }
        };
        BlockingQueue<Integer> completed = new LinkedBlockingQueue<>();
        try (RpcNode r = RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(remote).build();
             NodeFixture n = new NodeFixture(1, 1, p, 150)) {
            server.set(r); r.start();
            n.routing.register(101, 1, error -> { callbacks.incrementAndGet(); completed.add(error); });
            n.rpc.addPeer(101, r.localAddress(), 2);
            assertEquals(TIMEOUT, take(completed));
            Channel original = channel(n.rpc, 101, 0);
            await(n::isRegistered);
            assertTrue(attempts.get() >= 2); assertEquals(1, callbacks.get());
            assertSame(original, channel(n.rpc, 101, 0)); assertTrue(completed.isEmpty());
        } finally { p.release(); }
    }

    @Test void rejectedSelectionCanBeUnregisteredAndReplacedWithoutResettingHealthyRpcPeer() throws Exception {
        Probe p = new Probe();
        try (RouterFixture r = router(101); NodeFixture n = new NodeFixture(1, 1, p)) {
            r.routing.removeNode(1, 7);
            assertEquals(NOT_REGISTERED, n.attach(101, r.rpc.localAddress(), 1, 7).get(5, TimeUnit.SECONDS));
            Channel original = channel(n.rpc, 101, 0);
            assertEquals(0, n.detach().get(5, TimeUnit.SECONDS));
            assertSame(original, channel(n.rpc, 101, 0)); assertTrue(n.rpc.isPeerConnected(101));
            BlockingQueue<Integer> accepted = new LinkedBlockingQueue<>();
            n.routing.register(101, 8, accepted::add); assertEquals(0, take(accepted));
            assertTrue(n.isRegistered()); assertSame(original, channel(n.rpc, 101, 0));
            assertEquals(0, n.bind(42).get(5, TimeUnit.SECONDS));
            assertEquals(new RouteBinding(1, 8), r.resolve(1, 42).orElseThrow());
        } finally { p.release(); }
    }

    @Test void discoveryRemovalBeforeRegistrationAndAfterBindingFencesOnlyExactIncarnation() throws Exception {
        Probe p = new Probe();
        try (RouterFixture r = router(101)) {
            r.routing.removeNode(1, 7); // offline can precede the first REGISTER
            try (NodeFixture stale = new NodeFixture(1, 1, p)) {
                assertEquals(NOT_REGISTERED, stale.attach(101, r.rpc.localAddress(), 1, 7).get(5, TimeUnit.SECONDS));
                assertFalse(stale.isRegistered()); assertTrue(r.serviceNodes(1).isEmpty());
                BlockingQueue<Integer> retried = new LinkedBlockingQueue<>();
                stale.routing.register(101, 7, retried::add);
                assertEquals(NOT_REGISTERED, take(retried));
            }
            await(() -> !r.rpc.isPeerConnected(1));
            p.registrations.clear();
            try (NodeFixture current = new NodeFixture(1, 1, p)) {
                assertEquals(0, current.attach(101, r.rpc.localAddress(), 2, 8).get(5, TimeUnit.SECONDS));
                assertEquals(0, current.bind(42).get(5, TimeUnit.SECONDS));
                r.routing.removeNode(1, 7); assertTrue(r.resolve(1, 42).isPresent());
                r.routing.removeNode(1, 8);
                await(() -> !current.isRegistered());
                assertTrue(r.localBindings().isEmpty());
                current.rpc.removePeer(101); current.rpc.addPeer(101, r.rpc.localAddress(), 2);
                BlockingQueue<Integer> rejected = new LinkedBlockingQueue<>();
                await(() -> current.rpc.isPeerConnected(101));
                current.rpc.call(101, packet(nodeRegister(1, 8, 1)), (Integer error) -> rejected.add(error));
                assertEquals(NOT_REGISTERED, take(rejected));
                assertTrue(r.serviceNodes(1).isEmpty()); assertTrue(r.localBindings().isEmpty());
            }
        } finally { p.release(); }
    }

    private static io.netty.buffer.ByteBuf nodeRegister(int id, long epoch, int service) {
        return control(REGISTER).writeInt(id).writeLong(epoch).writeInt(service);
    }

    @Test void synchronousBusinessFailureRunsOutsideRoutingStateLockAndCanManagePeers() throws Exception {
        AtomicBoolean underLock = new AtomicBoolean(true); BlockingQueue<Integer> failed = new LinkedBlockingQueue<>();
        AtomicReference<NodeFixture> owner = new AtomicReference<>();
        Probe p = new Probe() {
            @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
                try { underLock.set(Thread.holdsLock(field(owner.get().routing, "lock"))); }
                catch (Exception e) { throw new AssertionError(e); }
                owner.get().rpc.addPeer(999, new java.net.InetSocketAddress("127.0.0.1", 1), 1);
                failed.add(error);
            }
        };
        try (RouterFixture r = router(101); NodeFixture n = client(1, 1, p, r)) {
            owner.set(n);
            for (int slot = 0; slot < 3; slot++) {
                final int index = slot;
                await(() -> { try { return channel(n.rpc, 101, index) != null; } catch (Exception e) { return false; } });
                Channel c = channel(n.rpc, 101, slot);
                c.eventLoop().submit(() -> c.unsafe().outboundBuffer().setUserDefinedWritability(1, false)).get(2, TimeUnit.SECONDS);
            }
            n.callNode(2, new RpcRequest(77, null), ignored -> {});
            assertEquals(UNAVAILABLE, take(failed)); assertFalse(underLock.get());
        } finally { p.release(); }
    }

    @Test void closingRoutingRejectsAnAlreadyDispatchedRegisterWithoutRepopulatingState() throws Exception {
        Probe p = new Probe(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameRouter router = GameRouter.forRouterNode(101, new Probe())) {
            RpcHandler delayed = new RpcHandler() {
                public void onRequest(int source, int slot, RpcRequest request) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release missing"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    router.onRequest(source, slot, request);
                }
                public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) { router.onResponse(source, command, response, callback); }
                public void onFail(int target, int command, int error, RpcCallback<?> callback) { router.onFail(target, command, error, callback); }
            };
            try (RpcNode r = RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(delayed).build(); NodeFixture n = new NodeFixture(1, 1, p)) {
                router.start(r); r.start();
                var registration = n.attach(101, r.localAddress(), 1, 1);
                assertTrue(entered.await(2, TimeUnit.SECONDS)); router.close(); release.countDown();
                assertEquals(HANDLER_ERROR, registration.get(5, TimeUnit.SECONDS));
                assertTrue(router.serviceNodes(1).isEmpty()); assertTrue(router.localBindings().isEmpty());
                assertTrue(r.isPeerConnected(1));
            }
        } finally { release.countDown(); p.release(); }
    }

    @Test void serviceControlAdmissionIsBoundedAndCloseCompletesEveryAdmittedCallbackOnce() throws Exception {
        Probe p = new Probe(); AtomicReference<RpcNode> server = new AtomicReference<>();
        CountDownLatch firstBind = new CountDownLatch(1); AtomicInteger callbacks = new AtomicInteger();
        Probe remote = new Probe() {
            public void onRequest(int source, int slot, RpcRequest request) {
                int op = operation(request.body().duplicate());
                if (op == CLIENT_BIND) { firstBind.countDown(); return; }
                server.get().reply(source, slot, request.routeKey(), new RpcResponse(request.requestId(), 0, null, Unpooled.buffer(8).writeLong(101)));
            }
        };
        try (RpcNode r = RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(remote).build(); NodeFixture n = new NodeFixture(1, 1, p)) {
            server.set(r); r.start(); assertEquals(0, n.attach(101, r.localAddress(), 1, 1).get(5, TimeUnit.SECONDS));
            RpcCallback<Integer> completed = error -> { assertEquals(NODE_CLOSED, error); callbacks.incrementAndGet(); };
            n.routing.bind(0, completed); assertTrue(firstBind.await(2, TimeUnit.SECONDS));
            for (int i = 1; i <= ServiceRouting.MAX_CONTROLS; i++) n.routing.bind(i, completed);
            assertThrows(RejectedExecutionException.class, () -> n.routing.bind(9000, completed));
            n.routing.close(); n.routing.close(); assertEquals(ServiceRouting.MAX_CONTROLS + 1, callbacks.get());
            assertTrue(n.rpc.isPeerConnected(101));
        } finally { p.release(); }
    }
}
