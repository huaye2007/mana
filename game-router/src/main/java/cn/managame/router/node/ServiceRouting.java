package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.error.RpcErrorCodes;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.router.call.*;
import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.*;
import static cn.managame.router.node.RouterWire.*;

/**
 * The service role on the process's existing RpcNode: sends routed messages through the declared Routers and
 * receives what they deliver. Every service connects to every Router. On each connected Router this Node
 * announces its incarnation and service (ATTACH) as soon as the connection is seen and again every second,
 * so a restarted Router learns it again without any registration exchange. Targets are exact Node IDs;
 * resolving a business key (a player, a room) to a Node belongs to the application.
 *
 * <p>Sends run on the caller and never wait for anything but RPC. A send picks one usable Router from the
 * target and routeKey, so one key to one target keeps one path while the set of usable Routers does not
 * change. A Router is usable once it has been connected for one announcement period, so the other Nodes
 * have announced themselves there too; when none is, any connected Router is used. A call that a Router
 * answers with PEER_NOT_FOUND or UNAVAILABLE was not delivered and is resent through another connected
 * Router. A routed call's response returns through the Router that carried the call.
 */
public final class ServiceRouting implements RpcHandler, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ServiceRouting.class.getName());
    private static final long TICK_MILLIS = 100, ATTACH_NANOS = TimeUnit.SECONDS.toNanos(1), WARMUP_NANOS = ATTACH_NANOS;

    /** A declared Router. attached and lastAttach belong to the attach thread; senders read up and upSince. */
    private static final class Link {
        final int id;
        boolean attached;           // ATTACH accepted on the current connection
        long lastAttach;
        volatile long upSince;      // when the attach thread first saw the current connection
        volatile boolean up;
        Link(int id) { this.id = id; }
    }

    private final int service;
    private final long epoch;
    private final RouterHandler handler;
    private final RpcHandler direct;
    private volatile Link[] routers = {};               // declared Routers, ascending ID; replaced, never mutated
    private volatile RpcNode rpc;
    private volatile boolean closed;
    private ScheduledExecutorService attacher;

    /**
     * @param serviceId the one service this Node serves, positive
     * @param nodeEpoch nonzero incarnation of this Node; a restarted process uses a new one
     * @param handler receives routed requests and the completions of routed calls
     * @param directHandler receives every other RPC request, response and failure of the Node
     */
    public ServiceRouting(int serviceId, long nodeEpoch, RouterHandler handler, RpcHandler directHandler) {
        if (serviceId <= 0 || nodeEpoch == 0) throw new IllegalArgumentException("invalid service or nodeEpoch");
        service = serviceId; epoch = nodeEpoch;
        this.handler = Objects.requireNonNull(handler); direct = Objects.requireNonNull(directHandler);
    }

    // ================================================================ lifecycle and Routers

    /** Associates the application-owned Node once and starts announcing it; it starts no networking. */
    public synchronized void start(RpcNode rpcNode) {
        Objects.requireNonNull(rpcNode);
        if (closed || rpc != null) throw new IllegalStateException("Routing already started or closed");
        rpc = rpcNode;
        attacher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread t = new Thread(task, "service-routing"); t.setDaemon(true); return t;
        });
        attacher.scheduleWithFixedDelay(this::attach, 0, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }
    /** Declares a Router. The application connects to it separately with RpcNode.addPeer. */
    public synchronized void addRouter(int routerId) {
        RpcNode node = running();
        if (routerId == 0 || routerId == node.nodeId()) throw new IllegalArgumentException("invalid Router ID");
        if (link(routerId) != null) return;
        Link[] next = Arrays.copyOf(routers, routers.length + 1);
        next[next.length - 1] = new Link(routerId);
        Arrays.sort(next, Comparator.comparingInt(l -> l.id));
        routers = next;
    }
    /** Stops using a Router and withdraws this Node from it; the RPC Peer is left to the application. */
    public synchronized void removeRouter(int routerId) {
        RpcNode node = running();
        if (link(routerId) == null) return;
        routers = Arrays.stream(routers).filter(l -> l.id != routerId).toArray(Link[]::new);
        // On the announcing thread, so no announcement already under way can follow the withdrawal.
        try { attacher.execute(() -> { if (node.isPeerConnected(routerId)) RouterWire.notify(node, routerId, detach(epoch)); }); }
        catch (RejectedExecutionException closing) { /* close withdraws from connected Routers itself */ }
    }
    /** Stops announcing and withdraws this Node from connected Routers. Idempotent; close the RpcNode afterwards. */
    @Override public void close() {
        RpcNode node; ScheduledExecutorService stopping;
        synchronized (this) {
            if (closed) return;
            closed = true; node = rpc; stopping = attacher;
        }
        if (stopping != null) {
            stopping.shutdown();
            try { stopping.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (node != null) for (Link l : routers) if (node.isPeerConnected(l.id)) RouterWire.notify(node, l.id, detach(epoch));
    }
    private RpcNode running() {
        RpcNode node = rpc;
        if (node == null) throw new IllegalStateException("Start routing first");
        if (closed) throw new IllegalStateException("Routing closed");
        return node;
    }
    private Link link(int routerId) {
        for (Link l : routers) if (l.id == routerId) return l;
        return null;
    }
    /** Announces this Node on every connected Router whose connection is new or whose last announcement is a second old. */
    private void attach() {
        try {
            if (closed) return;
            long now = System.nanoTime();
            for (Link l : routers) {
                if (!rpc.isPeerConnected(l.id)) { l.attached = false; l.up = false; continue; }
                if (!l.up) { l.upSince = now; l.up = true; }
                if (l.attached && now - l.lastAttach < ATTACH_NANOS) continue;
                if (RouterWire.notify(rpc, l.id, RouterWire.attach(epoch, service)) == RpcSendStatus.ACCEPTED) {
                    l.attached = true; l.lastAttach = now;
                }
            }
        } catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Router attachment failed", error); }
    }

    // ================================================================ sending (caller's thread)

    public RpcSendStatus notifyNode(int nodeId, RpcRequest request) {
        RpcNode node = validate(request, nodeId != 0);
        int router = router(node, nodeId, request.routeKey());
        return send(node, router, data(DIRECT, node.nodeId(), epoch, service, nodeId, 0, 0, request, maxInner(node)));
    }
    /** Every Node of the service attached to the chosen Router, including this one if it serves that service. */
    public RpcSendStatus broadcast(int serviceId, RpcRequest request) {
        RpcNode node = validate(request, serviceId > 0);
        int router = router(node, -serviceId, request.routeKey());
        return send(node, router, data(BROADCAST, node.nodeId(), epoch, service, 0, 0, serviceId, request, maxInner(node)));
    }
    public <T> void callNode(int nodeId, RpcRequest request, RpcCallback<T> callback) {
        call(nodeId, request, 0, callback);
    }
    public <T> void callNode(int nodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback) {
        if (timeoutMillis <= 0 || timeoutMillis > Long.MAX_VALUE / 1_000_000) throw new IllegalArgumentException("invalid timeout");
        call(nodeId, request, timeoutMillis, callback);
    }
    private <T> void call(int nodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback) {
        Objects.requireNonNull(callback);
        RpcNode node = validate(request, nodeId != 0);
        int router = router(node, nodeId, request.routeKey());
        RpcRequest out = data(DIRECT, node.nodeId(), epoch, service, nodeId, 0, 0, request, maxInner(node));
        Call c = new Call(nodeId, request.command(), callback, timeoutMillis, out.routeKey(), out.body().retainedDuplicate());
        try { c.start(node, router, out.body()); }
        catch (RuntimeException | Error error) { c.release(); throw error; }
    }
    /** Answers a routed call through the Router that delivered it, to the exact calling incarnation. */
    public RpcSendStatus reply(RoutedRequest request, RpcResponse response) {
        Objects.requireNonNull(request); Objects.requireNonNull(response);
        if (request.request().requestId() == 0 || request.request().requestId() != response.requestId())
            throw new IllegalArgumentException("response ID must match a routed call");
        RpcNode node = running();
        return send(node, request.routerId(), data(RESPONSE, node.nodeId(), epoch, service,
                request.sourceNodeId(), request.sourceNodeEpoch(), 0, response, maxInner(node)));
    }
    private RpcNode validate(RpcRequest request, boolean target) {
        Objects.requireNonNull(request);
        if (request.requestId() != 0 || !target) throw new IllegalArgumentException("invalid routed request/target");
        return running();
    }
    /** The Router for a first attempt; with none connected, the first declared Router, so RPC reports it unavailable. */
    private int router(RpcNode node, int target, long routeKey) {
        Link[] all = routers;
        if (all.length == 0) throw new IllegalStateException("No Router declared");
        int id = pick(node, all, target, routeKey, null);
        return id != 0 ? id : all[0].id;
    }
    /**
     * Picks from the target and routeKey among connected Routers that have been up for an announcement period,
     * else among all connected ones, skipping {@code tried}; 0 when none is connected.
     */
    private static int pick(RpcNode node, Link[] all, int target, long routeKey, int[] tried) {
        // Two passes over the (few) declared Routers instead of collecting candidates, so a send allocates nothing.
        long now = System.nanoTime();
        int warmCount = 0, upCount = 0;
        for (Link l : all) {
            if (!usable(node, l, tried)) continue;
            upCount++;
            if (warm(l, now)) warmCount++;
        }
        if (upCount == 0) return 0;
        long h = (routeKey ^ ((long) target << 32)) * 0x9E3779B97F4A7C15L;
        int hash = (int) (h ^ (h >>> 32));
        boolean onlyWarm = warmCount > 0;
        int remaining = Math.floorMod(hash, onlyWarm ? warmCount : upCount), fallback = 0;
        for (Link l : all) {
            if (!usable(node, l, tried) || (onlyWarm && !warm(l, now))) continue;
            if (remaining-- == 0) return l.id;
            fallback = l.id;
        }
        return fallback; // a connection dropped between the passes
    }
    private static boolean usable(RpcNode node, Link l, int[] tried) {
        return (tried == null || !contains(tried, l.id)) && node.isPeerConnected(l.id);
    }
    private static boolean warm(Link l, long now) {
        return l.up && now - l.upSince >= WARMUP_NANOS;
    }
    private static boolean contains(int[] ids, int id) {
        for (int i : ids) if (i == id) return true;
        return false;
    }
    private static RpcSendStatus send(RpcNode node, int router, RpcRequest out) {
        try { return node.notify(router, out); }
        catch (RuntimeException | Error error) { release(out.body()); throw error; }
    }

    /**
     * A routed call in flight. It keeps a reference to its encoded frame until it completes, so a call that a
     * Router could not deliver can be resent unchanged through another Router.
     */
    private final class Call implements RpcCallback<Object> {
        final int target, command;
        final RpcCallback<?> callback;
        final long timeoutMillis, routeKey;
        private ByteBuf frame;
        private int[] tried = {};
        Call(int target, int command, RpcCallback<?> callback, long timeoutMillis, long routeKey, ByteBuf frame) {
            this.target = target; this.command = command; this.callback = callback;
            this.timeoutMillis = timeoutMillis; this.routeKey = routeKey; this.frame = frame;
        }
        @Override public void onResponse(Object ignored) {}
        /** Starts one attempt; {@code body} shares the frame's reference count and is consumed. */
        void start(RpcNode node, int router, ByteBuf body) {
            ByteBuf held = frame;
            RpcRequest out = packet(routeKey, body);
            try {
                if (timeoutMillis == 0) node.call(router, out, this);
                else node.call(router, out, timeoutMillis, this);
            } catch (RuntimeException | Error error) {
                // RPC releases what it took; when it threw before taking the body, both references are still held.
                if (held.refCnt() > 1) body.release();
                throw error;
            }
        }
        /** Resends through a connected Router not tried yet; false when there is none or the attempt cannot start. */
        boolean resend(int failedRouter) {
            RpcNode node = rpc;
            if (closed || node == null || frame == null) return false;
            tried = Arrays.copyOf(tried, tried.length + 1);
            tried[tried.length - 1] = failedRouter;
            int router = pick(node, routers, target, routeKey, tried);
            if (router == 0) return false;
            try { start(node, router, frame.retainedDuplicate()); return true; }
            catch (RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Routed call resend failed", failure); return false; }
        }
        void release() {
            ByteBuf f = frame; frame = null;
            if (f != null) f.release();
        }
    }

    // ================================================================ RpcHandler (Netty threads)

    @Override public void onRequest(int source, int slot, RpcRequest request) {
        if (request.command() != COMMAND) { direct.onRequest(source, slot, request); return; }
        RpcNode node = rpc;
        if (node == null || request.requestId() != 0) throw new IllegalArgumentException("Invalid Router delivery");
        if (closed || link(source) == null) {
            // A Router this Node does not use still delivers here: an earlier DETACH was lost or overtaken.
            RouterWire.notify(node, source, detach(epoch));
            throw new IllegalArgumentException("Router delivery from an undeclared Router");
        }
        ByteBuf b = request.body().duplicate();
        if (operation(b) != DATA) throw new IllegalArgumentException("Services accept only routed data");
        Data d = data(b);
        if (d.mode() == RESPONSE || d.target() != node.nodeId() || d.targetEpoch() != epoch)
            throw new IllegalArgumentException("Delivery for another Node incarnation");
        RoutedRequest incoming = new RoutedRequest(d.source(), d.sourceEpoch(), d.request(), source);
        try { handler.onRoutedRequest(incoming); }
        catch (RuntimeException error) {
            int id = incoming.request().requestId();
            if (id != 0) {
                try { reply(incoming, new RpcResponse(id, RpcErrorCodes.HANDLER_ERROR, null, null)); }
                catch (RuntimeException replyFailure) { error.addSuppressed(replyFailure); }
            }
            throw error;
        }
    }
    @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
        if (!(callback instanceof Call c)) { direct.onResponse(source, command, response, callback); return; }
        int error = response.errorCode();
        // A positive outer error comes from the Router itself and has no envelope. These two mean "not delivered".
        if ((error == RpcErrorCodes.PEER_NOT_FOUND || error == RpcErrorCodes.UNAVAILABLE) && c.resend(source)) return;
        c.release();
        if (error != 0) { handler.onResponse(c.target, c.command, response, c.callback); return; }
        RpcResponse inner;
        try {
            ByteBuf b = response.body().duplicate();
            if (operation(b) != DATA) throw new IllegalArgumentException("Invalid routed response");
            Data d = data(b);
            if (d.mode() != RESPONSE || d.source() != c.target || d.target() != rpc.nodeId() || d.targetEpoch() != epoch
                    || d.requestId() != response.requestId()) throw new IllegalArgumentException("Response for another call");
            inner = d.response();
        } catch (RuntimeException malformed) {
            handler.onFail(c.target, c.command, RpcErrorCodes.PROTOCOL_ERROR, c.callback);
            return;
        }
        handler.onResponse(c.target, c.command, inner, c.callback);
    }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
        if (callback instanceof Call c) { c.release(); handler.onFail(c.target, c.command, error, c.callback); }
        else direct.onFail(target, command, error, callback);
    }
}
