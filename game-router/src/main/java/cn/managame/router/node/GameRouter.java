package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.error.RpcErrorCodes;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import static cn.managame.router.node.RouterWire.*;

/**
 * Stateless forwarding Router on the application-owned RpcNode. Every service connects to every Router and
 * sends routed messages through any of them; a Router relays each message to the target Node connected to
 * it. It keeps no routing table and talks to no other Router: the only thing it holds is the identity
 * (nodeEpoch, serviceId) announced on each live connection. RPC reports no disconnects, so an entry whose
 * connection closed is dropped when a send to it fails or when capacity runs out. Every message is handled
 * on the Netty thread that received it; there is no background thread.
 *
 * <p>Construct it before the RPC Node, pass it as the Node's handler and associate it through {@link #start};
 * the application closes both. Router Profile requests are relayed; every other request, and every
 * response (a Router never starts calls), goes to the application's direct handler.
 */
public final class GameRouter implements RpcHandler, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(GameRouter.class.getName());
    /** Attachments kept at once; past it, entries of closed connections are dropped first. */
    static final int MAX_NODES = 100_000;

    /**
     * Message counts since construction, for monitoring; every broadcast copy counts once.
     *
     * @param forwarded requests, broadcast copies and responses that RPC accepted for the next hop
     * @param targetNotFound requests whose target was not attached here (a Call was answered PEER_NOT_FOUND)
     * @param sendRefused messages to an attached Node that RPC did not accept: connection gone or write buffer full
     * @param responsesDropped responses whose caller was no longer attached with that epoch, or could not be sent
     */
    public record Stats(long forwarded, long targetNotFound, long sendRefused, long responsesDropped) {}

    /** The Node announced on a live connection. Identity equality, so a replaced entry is never removed by mistake. */
    private static final class Attachment {
        final long epoch; final int service;
        Attachment(long epoch, int service) { this.epoch = epoch; this.service = service; }
    }

    private final RpcHandler direct;
    /** Node ID → announced identity. Read lock-free on the data path; changed only under {@code writeLock}. */
    private final ConcurrentHashMap<Integer, Attachment> nodes = new ConcurrentHashMap<>();
    /** Service ID → attached Node IDs, kept consistent with {@code nodes} so broadcasts visit only that service. */
    private final ConcurrentHashMap<Integer, Set<Integer>> services = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final LongAdder forwarded = new LongAdder(), targetNotFound = new LongAdder(),
            sendRefused = new LongAdder(), responsesDropped = new LongAdder();
    private volatile RpcNode rpc;
    private volatile boolean closed;

    public GameRouter(RpcHandler directHandler) { direct = Objects.requireNonNull(directHandler); }

    /** Associates the application-owned Node once. It starts no networking and no background work. */
    public synchronized void start(RpcNode rpcNode) {
        Objects.requireNonNull(rpcNode);
        if (closed || rpc != null) throw new IllegalStateException("Router already started or closed");
        rpc = rpcNode;
    }
    /** Stops relaying and forgets every attachment. Idempotent; the application closes the RpcNode afterwards. */
    @Override public void close() { closed = true; clearAttachments(); }

    public Stats stats() {
        return new Stats(forwarded.sum(), targetNotFound.sum(), sendRefused.sum(), responsesDropped.sum());
    }
    /** Whether {@code nodeId} has announced itself on a connection that is still up. */
    public boolean isAttached(int nodeId) {
        RpcNode node = rpc;
        return !closed && node != null && nodes.containsKey(nodeId) && node.isPeerConnected(nodeId);
    }
    /** Nodes of the service attached over live connections; a snapshot, not a delivery guarantee. */
    public Set<Integer> serviceNodes(int serviceId) {
        if (serviceId <= 0) throw new IllegalArgumentException("serviceId must be positive");
        Set<Integer> ids = services.get(serviceId);
        if (ids == null) return Set.of();
        Set<Integer> result = new HashSet<>();
        for (int id : ids) if (isAttached(id)) result.add(id);
        return Set.copyOf(result);
    }

    // ---------------------------------------------------------------- RPC entry (Netty threads)

    @Override public void onRequest(int source, int slot, RpcRequest request) {
        if (request.command() == COMMAND) relay(source, slot, request);
        else direct.onRequest(source, slot, request);
    }
    @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
        direct.onResponse(source, command, response, callback);
    }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
        direct.onFail(target, command, error, callback);
    }

    private void relay(int source, int slot, RpcRequest request) {
        RpcNode node = rpc;
        if (closed || node == null) throw new IllegalStateException("Router not running");
        ByteBuf b = request.body().duplicate();
        int op = operation(b);
        if (op == DATA) { route(node, source, slot, request, data(b)); return; }
        if (request.requestId() != 0) throw new IllegalArgumentException("Router attachment controls are notifies");
        if (op == ATTACH) {
            need(b, 12);
            long epoch = b.readLong(); int service = b.readInt(); end(b);
            attach(source, epoch, service);
        } else if (op == DETACH) {
            need(b, 8);
            long epoch = b.readLong(); end(b);
            synchronized (writeLock) {
                Attachment current = nodes.get(source);
                if (current != null && current.epoch == epoch) removeAttachment(source, current);
            }
        } else throw new IllegalArgumentException("Unknown Router operation");
    }
    /** Records the Node announced on the connection from {@code id}; a new incarnation replaces the old one. */
    private void attach(int id, long epoch, int service) {
        if (epoch == 0 || service <= 0) throw new IllegalArgumentException("Invalid Node attachment");
        Attachment current = nodes.get(id);
        if (current != null && current.epoch == epoch && current.service == service) return;
        synchronized (writeLock) {
            if (!nodes.containsKey(id) && nodes.size() >= MAX_NODES) {
                RpcNode node = rpc;
                nodes.forEach((key, a) -> { if (!node.isPeerConnected(key)) removeAttachment(key, a); });
                if (nodes.size() >= MAX_NODES) throw new IllegalStateException("Router attachment capacity exceeded");
            }
            Attachment next = new Attachment(epoch, service), old = nodes.put(id, next);
            if (old != null && old.service != service) unindex(id, old.service);
            services.computeIfAbsent(service, ignored -> ConcurrentHashMap.newKeySet()).add(id);
        }
        if (closed) clearAttachments();                    // raced with close: leave nothing behind
    }
    /** Caller holds writeLock. */
    private boolean removeAttachment(int id, Attachment a) {
        if (!nodes.remove(id, a)) return false;
        unindex(id, a.service);
        return true;
    }
    /** Caller holds writeLock. */
    private void unindex(int id, int service) {
        Set<Integer> ids = services.get(service);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) services.remove(service, ids);
    }
    private void clearAttachments() {
        synchronized (writeLock) { nodes.clear(); services.clear(); }
    }

    // ---------------------------------------------------------------- data path

    /** Relays one DATA payload from the Node on {@code peer}; {@code outer} is a Call only for a DIRECT request. */
    private void route(RpcNode node, int peer, int slot, RpcRequest outer, Data d) {
        if (d.source() != peer) throw new IllegalArgumentException("Routed source must be the sending Node");
        attach(peer, d.sourceEpoch(), d.sourceService());   // a caller is reachable for its response at once
        switch (d.mode()) {
            case DIRECT -> {
                if (d.requestId() != 0) throw new IllegalArgumentException("Inner requestId must be zero");
                Attachment t = nodes.get(d.target());
                if (t == null || (d.targetEpoch() != 0 && d.targetEpoch() != t.epoch)) {
                    targetNotFound.increment();
                    fail(node, peer, slot, outer, RpcErrorCodes.PEER_NOT_FOUND);
                    return;
                }
                // The inner call ID becomes the caller's outer RPC call ID, so the response can complete it here.
                RpcSendStatus status = send(node, d.target(), t, forward(d, d.target(), t.epoch, outer.requestId()));
                if (status != RpcSendStatus.ACCEPTED)
                    fail(node, peer, slot, outer, status == RpcSendStatus.PEER_NOT_FOUND ? RpcErrorCodes.PEER_NOT_FOUND : RpcErrorCodes.UNAVAILABLE);
            }
            case BROADCAST -> {
                if (outer.requestId() != 0 || d.requestId() != 0) throw new IllegalArgumentException("Broadcast must be a notify");
                Set<Integer> ids = services.get(d.targetService());
                if (ids != null) for (int id : ids) {
                    Attachment a = nodes.get(id);
                    if (a != null && a.service == d.targetService()) send(node, id, a, forward(d, id, a.epoch, 0));
                }
            }
            case RESPONSE -> {
                if (outer.requestId() != 0) throw new IllegalArgumentException("Routed response must be a notify");
                Attachment t = nodes.get(d.target());
                // A caller that left or restarted has nothing waiting for this response. The Router never saw
                // the connection the call left on, so the response takes any of the caller's writable Slots.
                boolean sent = t != null && t.epoch == d.targetEpoch() && forwardResponse(node, d.target(),
                        new RpcResponse(d.requestId(), 0, null, forward(d, d.target(), t.epoch, 0).body())) == RpcSendStatus.ACCEPTED;
                (sent ? forwarded : responsesDropped).increment();
            }
            default -> throw new IllegalArgumentException("Unknown routed mode");
        }
    }
    /** Sends to an attached Node; a refused send to a Node whose connection is gone forgets that attachment. */
    private RpcSendStatus send(RpcNode node, int id, Attachment a, RpcRequest message) {
        RpcSendStatus status = RouterWire.notify(node, id, message);
        if (status == RpcSendStatus.ACCEPTED) { forwarded.increment(); return status; }
        sendRefused.increment();
        if (!node.isPeerConnected(id)) synchronized (writeLock) { removeAttachment(id, a); }
        return status;
    }
    /** Answers the caller's outer Call at once; a Notify has nobody waiting. */
    private void fail(RpcNode node, int peer, int slot, RpcRequest outer, int error) {
        if (outer.requestId() != 0) reply(node, peer, slot, new RpcResponse(outer.requestId(), error, null, null));
    }
    private RpcSendStatus reply(RpcNode node, int id, int slot, RpcResponse message) {
        try { return node.reply(id, slot, message); }
        catch (RuntimeException error) {
            release(message.body());
            LOG.log(System.Logger.Level.WARNING, "Router reply failed", error);
            return RpcSendStatus.UNAVAILABLE;
        }
    }
    private RpcSendStatus forwardResponse(RpcNode node, int id, RpcResponse message) {
        try { return node.forwardResponse(id, 0, message); }
        catch (RuntimeException error) {
            release(message.body());
            LOG.log(System.Logger.Level.WARNING, "Router response relay failed", error);
            return RpcSendStatus.UNAVAILABLE;
        }
    }
}
