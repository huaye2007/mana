package cn.managame.router.node;

import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.router.route.*;
import cn.managame.router.node.GameRouter;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.managame.router.node.RouterIntegrationTest.*;
import static cn.managame.router.node.RouterWire.*;
import static org.junit.jupiter.api.Assertions.*;

/** Router-to-Router stream: snapshot + ordered deltas, gap detection, resynchronization and convergence. */
class RouterSyncTest {
    @BeforeAll static void setup() throws Exception { environment(); }

    private static Object field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }
    private static Object link(RouterFixture r, int peer) throws Exception {
        return ((Map<?, ?>) field(field(r.routing, "engine"), "links")).get(peer);
    }
    private static long replicaField(RouterFixture r, int peer, String name) {
        try {
            Object l = link(r, peer);
            synchronized (field(l, "replicaLock")) { return (long) field(l, name); }
        } catch (Exception e) { throw new AssertionError(e); }
    }
    /** Every binding the authority holds is resolvable at the replica, and nothing else. */
    private static boolean converged(RouterFixture authority, RouterFixture replica, int service, long keys) {
        Map<BindingKey, RouteBinding> expected = authority.localBindings();
        for (long k = 0; k < keys; k++) {
            RouteBinding want = expected.get(new BindingKey(service, k));
            Optional<RouteBinding> got = replica.resolve(service, k);
            if (want == null ? got.isPresent() : !got.equals(Optional.of(want))) return false;
        }
        return true;
    }
    private static void all(List<Promise<Integer>> pending) throws Exception {
        for (Promise<Integer> p : pending) assertEquals(0, p.get(10, TimeUnit.SECONDS));
        pending.clear();
    }

    @Test void largeSnapshotWithConcurrentChurnConvergesExactly() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture n = client(1, 5, p, a)) {
            List<Promise<Integer>> pending = new ArrayList<>();
            for (int i = 0; i < 6000; i++) pending.add(n.bind(i));
            all(pending);
            b.routing.registerRouter(101);
            a.addRouter(102, b.rpcNode().localAddress());
            // Changes made while the snapshot streams must follow it in order.
            for (int i = 0; i < 6000; i += 3) pending.add(n.unbind(i));
            for (int i = 6000; i < 7000; i++) pending.add(n.bind(i));
            all(pending);
            await(() -> b.isRouterReady(101) && converged(a, b, 5, 7000));
            assertEquals(4000 + 1000, a.localBindings().size());
        } finally { p.release(); }
    }

    @Test void fourRouterMeshConvergesAndRouterRestartReplacesItsReplica() throws Exception {
        Probe[] probes = {new Probe(), new Probe(), new Probe(), new Probe()};
        RouterFixture[] r = {router(101), router(102), router(103), router(104)};
        List<NodeFixture> nodes = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) nodes.add(client(i + 1, 1, probes[i], r[i]));
            for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) connect(r[i], r[j]);
            List<Promise<Integer>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) for (int k = 0; k < 500; k++) pending.add(nodes.get(i).bind(i * 1000L + k));
            all(pending);
            await(() -> {
                for (RouterFixture x : r) for (int i = 0; i < 4; i++)
                    if (!x.resolve(1, i * 1000L + 499).equals(Optional.of(new RouteBinding(i + 1, i + 1)))) return false;
                return true;
            });
            assertEquals(Set.of(1, 2, 3, 4), r[0].serviceNodes(1));
            // Router 104 restarts (new incarnation, empty bucket): its old replica is replaced, not merged.
            var address = r[3].rpcNode().localAddress();
            nodes.get(3).close(); r[3].close();
            r[3] = new RouterFixture(104, address, 404);
            for (int i = 0; i < 3; i++) r[3].routing.registerRouter(101 + i);
            await(() -> r[0].isRouterReady(104) && r[3].isRouterReady(101) && r[0].resolve(1, 3000).isEmpty() && !r[0].serviceNodes(1).contains(4));
            assertEquals(Optional.of(new RouteBinding(1, 1)), r[3].resolve(1, 0));
        } finally {
            for (NodeFixture n : nodes) n.close();
            for (RouterFixture x : r) x.close();
            for (Probe p : probes) p.release();
        }
    }

    @Test void repeatedConnectionLossDuringChurnStillConvergesExactly() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture n = client(1, 7, p, a)) {
            connect(a, b);
            Random random = new Random(7);
            List<Promise<Integer>> pending = new ArrayList<>();
            for (int round = 0; round < 6; round++) {
                for (int i = 0; i < 400; i++) {
                    long key = random.nextInt(1500);
                    pending.add(n.bindings().contains(key) || random.nextBoolean() ? n.unbind(key) : n.bind(key));
                }
                if (round % 2 == 0) { a.rpc.removePeer(102); a.rpc.addPeer(102, b.rpc.localAddress(), 1); }
                for (Promise<Integer> f : pending) f.get(10, TimeUnit.SECONDS);
                pending.clear();
            }
            try { await(() -> a.isRouterReady(102) && b.isRouterReady(101) && converged(a, b, 7, 1500)); }
            catch (AssertionError failure) {
                System.out.println("DIAG readyA=" + a.isRouterReady(102) + " readyB=" + b.isRouterReady(101)
                        + " connA=" + a.rpc.isPeerConnected(102) + " connB=" + b.rpc.isPeerConnected(101));
                for (String f : List.of("inEpoch", "inSession", "expectSeq", "brokenEpoch", "brokenSession"))
                    System.out.println("DIAG b<-a " + f + "=" + replicaField(b, 101, f) + "  a<-b " + f + "=" + replicaField(a, 102, f));
                Object la = link(a, 102);
                synchronized (la) { System.out.println("DIAG a->b session=" + field(la, "session") + " seq=" + field(la, "seq") + " queue=" + ((Collection<?>) field(la, "queue")).size()); }
                int missing = 0, extra = 0;
                Map<BindingKey, RouteBinding> expected = a.localBindings();
                for (long k = 0; k < 1500; k++) {
                    boolean want = expected.containsKey(new BindingKey(7, k)), got = b.resolve(7, k).isPresent();
                    if (want && !got) missing++; if (!want && got) extra++;
                }
                System.out.println("DIAG missing=" + missing + " extra=" + extra + " size=" + expected.size());
                throw failure;
            }
        } finally { p.release(); }
    }

    @Test void framesOfAnAbandonedSessionAreIgnored() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); RouterFixture b = router(102); NodeFixture n = client(1, 1, p, a)) {
            assertEquals(0, n.bind(1).get(5, TimeUnit.SECONDS)); connect(a, b);
            await(() -> b.resolve(1, 1).isPresent());
            long first = replicaField(b, 101, "inSession");
            assertEquals(RpcSendStatus.ACCEPTED, a.rpc.notify(102, packet(sync(DELTA, 101, Long.MAX_VALUE, 1, 0).writeShort(0))));
            await(() -> replicaField(b, 101, "inSession") != first && b.isRouterReady(101));
            long second = replicaField(b, 101, "inSession");
            // A stale DELTA of the first session, even with a wrong sequence, neither breaks nor changes the replica.
            assertEquals(RpcSendStatus.ACCEPTED, a.rpc.notify(102, packet(sync(DELTA, 101, first, 999, 0).writeShort(0))));
            assertEquals(0, n.bind(2).get(5, TimeUnit.SECONDS));
            await(() -> b.resolve(1, 2).isPresent());
            assertEquals(second, replicaField(b, 101, "inSession")); assertTrue(b.isRouterReady(101));
        } finally { p.release(); }
    }

    @Test void lookupsNeverBlockWhileTheAuthorityIsBusy() throws Exception {
        Probe p = new Probe();
        try (RouterFixture a = router(101); NodeFixture n = client(1, 1, p, a)) {
            assertEquals(0, n.bind(9).get(5, TimeUnit.SECONDS));
            Object engine = field(a.routing, "engine");
            CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread owner = Thread.ofPlatform().start(() -> {
                try { synchronized (field(engine, "authority")) { holding.countDown(); release.await(); } }
                catch (Exception e) { throw new AssertionError(e); }
            });
            try {
                assertTrue(holding.await(2, TimeUnit.SECONDS));
                AtomicInteger resolved = new AtomicInteger();
                Thread reader = Thread.ofPlatform().start(() -> {
                    if (a.resolve(1, 9).isPresent()) resolved.incrementAndGet();
                    if (a.serviceNodes(1).contains(1)) resolved.incrementAndGet();
                });
                reader.join(2000);
                assertEquals(2, resolved.get());
            } finally { release.countDown(); owner.join(); }
        } finally { p.release(); }
    }

    /** Loses random Router stream frames (as a replaced connection would), in both directions. */
    private static cn.managame.rpc.call.RpcHandler lossy(GameRouter router, java.util.function.DoubleSupplier rate, Random random) {
        return new cn.managame.rpc.call.RpcHandler() {
            @Override public void onRequest(int source, int slot, cn.managame.rpc.message.RpcRequest request) {
                if (request.command() == COMMAND) {
                    int op = operation(request.body().duplicate());
                    if (op >= SYNC && op <= STATE) synchronized (random) { if (random.nextDouble() < rate.getAsDouble()) return; }
                }
                router.onRequest(source, slot, request);
            }
            @Override public void onResponse(int source, int command, cn.managame.rpc.message.RpcResponse response, cn.managame.rpc.call.RpcCallback<?> callback) {
                router.onResponse(source, command, response, callback);
            }
            @Override public void onFail(int target, int command, int error, cn.managame.rpc.call.RpcCallback<?> callback) {
                router.onFail(target, command, error, callback);
            }
        };
    }

    @Test void randomFrameLossInBothDirectionsConvergesOnceTheLinkIsQuiet() throws Exception {
        Random random = new Random(11);
        java.util.concurrent.atomic.AtomicReference<Double> rate = new java.util.concurrent.atomic.AtomicReference<>(0.03);
        GameRouter ga = GameRouter.forRouterNode(101, new Probe()), gb = GameRouter.forRouterNode(102, new Probe());
        Probe pa = new Probe(), pb = new Probe();
        try (ga; gb;
             var ra = cn.managame.rpc.node.RpcNode.builder().nodeId(101).bindAddress(LOCAL).handler(lossy(ga, rate::get, random)).build();
             var rb = cn.managame.rpc.node.RpcNode.builder().nodeId(102).bindAddress(LOCAL).handler(lossy(gb, rate::get, random)).build()) {
            ga.start(ra); gb.start(rb); ra.start(); rb.start();
            try (NodeFixture na = new NodeFixture(1, 3, pa); NodeFixture nb = new NodeFixture(2, 3, pb)) {
                assertEquals(0, na.attach(101, ra.localAddress(), 1, 1).get(5, TimeUnit.SECONDS));
                assertEquals(0, nb.attach(102, rb.localAddress(), 1, 2).get(5, TimeUnit.SECONDS));
                gb.registerRouter(101); ga.registerRouter(102); ra.addPeer(102, rb.localAddress(), 1);
                List<Promise<Integer>> pending = new ArrayList<>();
                for (int round = 0; round < 8; round++) {
                    for (int i = 0; i < 300; i++) {
                        NodeFixture n = random.nextBoolean() ? na : nb;
                        long key = (n == na ? 0 : 10_000) + random.nextInt(1000);
                        pending.add(random.nextBoolean() ? n.bind(key) : n.unbind(key));
                    }
                    for (Promise<Integer> f : pending) f.get(10, TimeUnit.SECONDS);
                    pending.clear();
                    Thread.sleep(50);
                }
                rate.set(0.0);
                await(() -> ga.isRouterReady(102) && gb.isRouterReady(101) && sameView(ga, gb, 3));
            }
        } finally { pa.release(); pb.release(); }
    }
    private static boolean sameView(GameRouter a, GameRouter b, int service) {
        for (long k = 0; k < 11_000; k++) {
            if (k == 1000) k = 10_000;
            if (!a.resolve(service, k).equals(b.resolve(service, k))) return false;
        }
        Map<BindingKey, RouteBinding> all = new HashMap<>(a.localBindings()); all.putAll(b.localBindings());
        for (var e : all.entrySet()) if (!a.resolve(service, e.getKey().bindingKey()).equals(Optional.of(e.getValue()))) return false;
        return true;
    }
}
