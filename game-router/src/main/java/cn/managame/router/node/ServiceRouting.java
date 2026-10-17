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
 * The service role on the process's existing RpcNode: sends routed messages through the one Router this Node
 * is attached to and receives what that Router delivers. The application declares the Routers this Node may
 * use and owns the RPC Peer, so it decides which one is connected; this role uses the connected declared
 * Router with the lowest ID, announces its incarnation and service (ATTACH) there as soon as it sees the
 * connection and again every second, and withdraws from a Router it stops using. A restarted Router therefore
 * learns this Node again without any registration exchange.
 *
 * <p>Sends run on the caller and never wait for anything but RPC. There is no Router choice and no warm-up:
 * one connection means one path, so messages with one routeKey to one target keep their order while that
 * connection and the target's location are unchanged. A Router answers PEER_NOT_FOUND only when it can see no
 * location for the target anywhere in the mesh it reaches, so such a Call is reported rather than resent. A
 * routed call's response returns through the same Router, which the mesh resolves from this Node's location.
 */
public final class ServiceRouting implements RpcHandler, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ServiceRouting.class.getName());
    private static final long TICK_MILLIS = 100, ATTACH_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final int service;
    private final long epoch;
    private final RouterHandler handler;
    private final RpcHandler direct;
    private volatile int[] declared = {};               // declared Router IDs, ascending; replaced, never mutated
    private volatile int current;                       // the Router this Node is attached to, 0 when none
    private boolean attached;                           // ATTACH accepted on the current connection
    private long lastAttach;                            // both belong to the attach thread
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
    /**
     * Declares a Router this Node may use. The application connects to one of them separately with
     * RpcNode.addPeer; this role attaches to the connected declared Router with the lowest ID.
     */
    public synchronized void addRouter(int routerId) {
        RpcNode node = running();
        if (routerId == 0 || routerId == node.nodeId()) throw new IllegalArgumentException("invalid Router ID");
        if (declares(routerId)) return;
        int[] next = Arrays.copyOf(declared, declared.length + 1);
        next[next.length - 1] = routerId;
        Arrays.sort(next);
        declared = next;
    }
    /** Stops using a Router and withdraws this Node from it; the RPC Peer is left to the application. */
    public synchronized void removeRouter(int routerId) {
        RpcNode node = running();
        if (!declares(routerId)) return;
        declared = Arrays.stream(declared).filter(id -> id != routerId).toArray();
        // Stop sending there at once rather than at the next tick: the application has stopped using it.
        if (current == routerId) current = 0;
        // On the announcing thread, so no announcement already under way can follow the withdrawal.
        try { attacher.execute(() -> withdraw(node, routerId)); }
        catch (RejectedExecutionException closing) { /* close withdraws from the current Router itself */ }
    }
    /** Stops announcing and withdraws this Node from its Router. Idempotent; close the RpcNode afterwards. */
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
        if (node != null) withdraw(node, current);
    }
    private RpcNode running() {
        RpcNode node = rpc;
        if (node == null) throw new IllegalStateException("Start routing first");
        if (closed) throw new IllegalStateException("Routing closed");
        return node;
    }
    private boolean declares(int routerId) {
        for (int id : declared) if (id == routerId) return true;
        return false;
    }
    private void withdraw(RpcNode node, int routerId) {
        if (routerId != 0 && node.isPeerConnected(routerId)) RouterWire.notify(node, routerId, detach(epoch));
    }
    /**
     * Keeps this Node attached to exactly one Router: the connected declared Router with the lowest ID. A
     * change withdraws from the previous one first, so the Node is never announced in two places.
     */
    private void attach() {
        try {
            if (closed) return;
            RpcNode node = rpc;
            if (node == null) return;
            int chosen = 0;
            for (int id : declared) if (node.isPeerConnected(id)) { chosen = id; break; }
            if (chosen != current) {
                withdraw(node, current);
                current = chosen;
                attached = false;
            }
            if (chosen == 0) return;
            long now = System.nanoTime();
            if (attached && now - lastAttach < ATTACH_NANOS) return;
            if (RouterWire.notify(node, chosen, RouterWire.attach(epoch, service)) == RpcSendStatus.ACCEPTED) {
                attached = true; lastAttach = now;
            }
        } catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Router attachment failed", error); }
    }

    // ================================================================ sending (caller's thread)

    public RpcSendStatus notifyNode(int nodeId, RpcRequest request) {
        RpcNode node = validate(request, nodeId != 0);
        return send(node, router(), data(DIRECT, node.nodeId(), epoch, service, nodeId, 0, 0, request, maxInner(node)));
    }
    /** Every Node of the service attached anywhere in the mesh this Node's Router can reach. */
    public RpcSendStatus broadcast(int serviceId, RpcRequest request) {
        RpcNode node = validate(request, serviceId > 0);
        return send(node, router(), data(BROADCAST, node.nodeId(), epoch, service, 0, 0, serviceId, request, maxInner(node)));
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
        int router = router();
        RpcRequest out = data(DIRECT, node.nodeId(), epoch, service, nodeId, 0, 0, request, maxInner(node));
        Call c = new Call(nodeId, request.command(), callback);
        try {
            if (timeoutMillis == 0) node.call(router, out, c);
            else node.call(router, out, timeoutMillis, c);
        } catch (RuntimeException | Error error) { release(out.body()); throw error; }
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
    /** The Router this Node is attached to; with none, the first declared one, so RPC reports it unavailable. */
    private int router() {
        int chosen = current;
        if (chosen != 0) return chosen;
        int[] all = declared;
        if (all.length == 0) throw new IllegalStateException("No Router declared");
        return all[0];
    }
    private static RpcSendStatus send(RpcNode node, int router, RpcRequest out) {
        try { return node.notify(router, out); }
        catch (RuntimeException | Error error) { release(out.body()); throw error; }
    }

    /** A routed call in flight: the business target and command, so a completion reaches the RouterHandler. */
    private record Call(int target, int command, RpcCallback<?> callback) implements RpcCallback<Object> {
        @Override public void onResponse(Object ignored) {}
    }

    // ================================================================ RpcHandler (Netty threads)

    @Override public void onRequest(int source, int slot, RpcRequest request) {
        if (request.command() != COMMAND) { direct.onRequest(source, slot, request); return; }
        RpcNode node = rpc;
        if (node == null || request.requestId() != 0) throw new IllegalArgumentException("Invalid Router delivery");
        if (closed || !declares(source)) {
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
        // A positive outer error comes from a Router itself and has no envelope.
        if (error != 0) { handler.onResponse(c.target(), c.command(), response, c.callback()); return; }
        RpcResponse inner;
        try {
            ByteBuf b = response.body().duplicate();
            if (operation(b) != DATA) throw new IllegalArgumentException("Invalid routed response");
            Data d = data(b);
            if (d.mode() != RESPONSE || d.source() != c.target() || d.target() != rpc.nodeId() || d.targetEpoch() != epoch
                    || d.requestId() != response.requestId()) throw new IllegalArgumentException("Response for another call");
            inner = d.response();
        } catch (RuntimeException malformed) {
            handler.onFail(c.target(), c.command(), RpcErrorCodes.PROTOCOL_ERROR, c.callback());
            return;
        }
        handler.onResponse(c.target(), c.command(), inner, c.callback());
    }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
        if (callback instanceof Call c) handler.onFail(c.target(), c.command(), error, c.callback());
        else direct.onFail(target, command, error, callback);
    }
}
