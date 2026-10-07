package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.router.call.RouterHandler;
import cn.managame.router.route.RouteBinding;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.junit.jupiter.api.*;
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
