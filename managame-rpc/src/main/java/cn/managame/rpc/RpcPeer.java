package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_NODE_CLOSED;
import static cn.managame.FrameworkErrorCodes.RPC_PEER_REMOVED;
import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.netty.NetworkClient;
import cn.managame.rpc.RpcFrames.Goodbye;
import cn.managame.rpc.RpcFrames.Hello;
import io.netty.util.AttributeKey;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Another node, known by its id: one this node dials ({@link RpcNode#createPeer}) or one that dials this node and
 * joins when its handshake completes. Either way every connection carries calls, notifies and answers both ways.
 *
 * <pre>{@code
 * peer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> ...);
 * peer.notify(RpcNotify.of(MAIL_ARRIVED, playerId, body));
 * }</pre>
 *
 * A peer may have several connections. A call or notify takes the one its route key picks, so messages with one key
 * reach the peer in the order they were sent, as long as the peer's connections do not change. A peer without a
 * connection stays known until it is removed, and the node dialing in again rejoins the same instance.
 *
 * <p>Each running instance of a node has its own {@link #incarnation()}, so a restart is told from a reconnect.
 * Only one instance of a peer is connected at a time: a newer one replaces the connected one, whose connections
 * close and whose calls fail, and an older one is refused while a newer one is connected.
 *
 * <p>The calls waiting for an answer are the peer's, each remembering the connection it went out on: an answer
 * settles it, and so do its timeout, the close of that connection, the removal of the peer and the close of the node.
 *
 * <p>A message handed to {@link #call} or {@link #notify(RpcNotify)} becomes the node's, which releases it once
 * sent and also when it cannot be sent, so the caller must not use it afterwards; to pass on a message received in
 * the handler, retain it first.
 */
public final class RpcPeer {
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    /** The peer a connection belongs to, once its handshake is done. */
    private static final AttributeKey<RpcPeer> KEY = AttributeKey.valueOf("cn.managame.rpc.peer");
    private static final Connection[] NONE = {};
    private final RpcNode node;
    private final int nodeId;
    private final boolean outbound;
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger requestIds = new AtomicInteger();
    /** The dialers keeping the connections to an outbound peer up. */
    private volatile List<NetworkClient> clients = List.of();
    private volatile Connection[] connections = NONE;
    private volatile Metadata metadata = Metadata.empty();
    private volatile long incarnation;
    private volatile boolean closing, removed;

    RpcPeer(RpcNode node, int nodeId, boolean outbound) {
        this.node = node;
        this.nodeId = nodeId;
        this.outbound = outbound;
    }

    public int nodeId() { return nodeId; }

    /**
     * The running instance of the node last connected, which a restart changes; 0 before the first handshake. Newer
     * instances have larger incarnations as long as the clocks of the machines they start on roughly agree.
     */
    public long incarnation() { return incarnation; }

    /** What the peer sent in its latest handshake: whatever its application put there. Empty before the first. */
    public Metadata metadata() { return metadata; }

    /** True when this node dials the peer, false when the peer dials this node. */
    public boolean isOutbound() { return outbound; }

    /** Whether at least one connection has completed its handshake, so that calls can be sent. */
    public boolean isConnected() { return connections.length > 0; }

    /** Whether the connected instance said it is closing: calls and notifies to it then fail at once. */
    public boolean isClosing() { return closing; }

    public int connectionCount() { return connections.length; }

    /** The calls to the peer waiting for their answer. */
    public int pendingCount() { return pending.size(); }

    /** {@link #call(RpcRequest, Duration, RpcCallback)} with the node's timeout. */
    public void call(RpcRequest request, RpcCallback callback) { call(request, node.timeout(), callback); }

    /**
     * Sends {@code request} under a new request id and a deadline {@code timeout} away, on the connection its route
     * key picks, and hands the answer to {@code callback}, exactly once. Nothing about the network throws: the
     * callback receives the callee's answer, which may be a failure code of the callee's, or one of
     * <ul>
     * <li>RPC_UNAVAILABLE: no connection is up, the peer is closing, the connection is too busy to take the request,
     * or it closed before the answer came;</li>
     * <li>RPC_TIMEOUT: no answer within {@code timeout};</li>
     * <li>RPC_PROTOCOL_ERROR: the request, or its answer, is larger than the largest frame;</li>
     * <li>RPC_PEER_REMOVED: the peer was removed, before or during the call;</li>
     * <li>RPC_NODE_CLOSED: this node was closed, before or during the call.</li>
     * </ul>
     * A null argument or a timeout under a millisecond throws, after releasing the request.
     */
    public void call(RpcRequest request, Duration timeout, RpcCallback callback) {
        Objects.requireNonNull(request, "request");
        long timeoutNanos;
        try {
            Objects.requireNonNull(callback, "callback");
            if (timeout.toMillis() < 1) throw new IllegalArgumentException("timeout must be at least one millisecond");
            timeoutNanos = timeout.toNanos();
        } catch (RuntimeException e) {
            request.release();
            throw e;
        }
        long start = System.nanoTime();
        long deadline = start + timeoutNanos;
        int id = nextRequestId();
        RpcRequest sent = request.sentAs(id, deadline != 0 ? deadline : 1);
        Connection connection = connection(sent.routeKey());
        Pending call = new Pending(sent.withoutBody(), callback, connection, start);
        pending.put(id, call);
        // Read after the put: a removal or close either fails the call or is seen here.
        int failure = node.isClosed() ? RPC_NODE_CLOSED : removed ? RPC_PEER_REMOVED
                : connection == null || closing ? RPC_UNAVAILABLE
                : !RpcFrames.fits(sent, node.maxFrameLength()) ? RPC_PROTOCOL_ERROR : 0;
        if (failure == 0) {
            call.timeout = node.newTimeout(call, timeoutNanos);
            if (RpcFrames.send(connection, sent)) return;
            failure = RPC_UNAVAILABLE;
        } else {
            if (failure == RPC_PROTOCOL_ERROR) LOG.log(Level.WARNING, "Request " + sent.command() + " to " + this + " is larger than the largest frame");
            sent.release();
        }
        settle(id, call, failure);
    }

    /**
     * Sends {@code notify} on the connection its route key picks. False when it cannot be sent: no connection is up
     * or it is too busy, the peer is closing or was removed, this node closed, or the notify is larger than the
     * largest frame. True means the connection took it, though it may still close before the notify leaves.
     */
    public boolean notify(RpcNotify notify) {
        Objects.requireNonNull(notify, "notify");
        Connection connection = node.isClosed() || removed || closing ? null : connection(notify.routeKey());
        if (connection != null) {
            if (RpcFrames.fits(notify, node.maxFrameLength())) return RpcFrames.send(connection, notify);
            LOG.log(Level.WARNING, "Notify " + notify.command() + " to " + this + " is larger than the largest frame");
        }
        notify.release();
        return false;
    }

    /** Ids are the peer's own, never 0. */
    private int nextRequestId() {
        int id = requestIds.incrementAndGet();
        return id != 0 ? id : requestIds.incrementAndGet();
    }

    private Connection connection(long routeKey) {
        Connection[] current = connections;
        return switch (current.length) {
            case 0 -> null;
            case 1 -> current[0];
            default -> current[Math.floorMod(routeKey, current.length)];
        };
    }

    /** The peer {@code connection} belongs to, or null before its handshake. */
    static RpcPeer of(Connection connection) { return connection.get(KEY); }

    /** An answer from the peer: it settles its call, if that still waits. */
    void answer(RpcResponse response) {
        Pending call = pending.remove(response.requestId());
        if (call != null) call.complete(response);
    }

    private void settle(int id, Pending call, int code) {
        if (pending.remove(id, call)) call.complete(RpcResponse.failure(id, code));
    }

    /** Fails every waiting call, or those sent on {@code connection} when it is not null. */
    private void settleAll(Connection connection, int code) {
        pending.forEach((id, call) -> {
            if (connection == null || call.connection == connection) settle(id, call, code);
        });
    }

    void dial(List<NetworkClient> clients) { this.clients = List.copyOf(clients); }

    boolean hasWaitingCalls() { return !pending.isEmpty(); }

    /**
     * A connection completed its handshake with an instance of the peer, and joins it unless refused: when the peer
     * was removed or the node closed, or an older instance dials while a newer one is connected. A newer instance
     * than the connected one replaces it. {@code beforeJoining} runs once the connection is accepted and before
     * anyone can use it. Returns why the connection was refused, or null.
     */
    String attach(Connection connection, Hello hello, Runnable beforeJoining) {
        Connection[] replaced = NONE;
        synchronized (this) {
            if (removed || node.isClosed()) return "node " + nodeId + " was removed, or this node closed";
            long known = incarnation;
            if (connections.length > 0 && hello.incarnation() != known) {
                if (hello.incarnation() < known) return "an older instance of node " + nodeId + " dials while a newer one is connected";
                LOG.log(Level.WARNING, "A newer instance of node " + nodeId + " replaces the connected one");
                replaced = connections;
                connections = NONE;
                node.disconnected(this);
            }
            boolean restarted = known != 0 && hello.incarnation() != known;
            if (known != hello.incarnation()) {
                incarnation = hello.incarnation();
                closing = false;
            }
            metadata = hello.metadata();
            beforeJoining.run();
            connection.set(KEY, this);
            Connection[] grown = Arrays.copyOf(connections, connections.length + 1);
            grown[connections.length] = connection;
            connections = grown;
            if (grown.length == 1) node.connected(this, restarted);
        }
        for (Connection old : replaced) {
            settleAll(old, RPC_UNAVAILABLE);
            old.close();
        }
        return null;
    }

    /** A connection closed: the calls sent on it can no longer be answered. */
    void detach(Connection connection) {
        synchronized (this) {
            Connection[] current = connections;
            Connection[] rest = Arrays.stream(current).filter(other -> other != connection).toArray(Connection[]::new);
            if (rest.length < current.length) {
                connections = rest.length == 0 ? NONE : rest;
                if (rest.length == 0) node.disconnected(this);
            }
        }
        settleAll(connection, RPC_UNAVAILABLE);
    }

    /** The peer said Goodbye. */
    synchronized void closing() {
        if (closing) return;
        closing = true;
        node.closing(this);
    }

    /** This node is closing: the peer hears Goodbye on every connection. */
    void goodbye() {
        for (Connection connection : connections) RpcFrames.send(connection, Goodbye.INSTANCE);
    }

    /** Fails the waiting calls with RPC_PEER_REMOVED, then stops dialing and closes every connection. */
    void remove() {
        Connection[] open;
        synchronized (this) {
            removed = true;
            open = connections;
        }
        settleAll(null, RPC_PEER_REMOVED);
        stopDialing();
        for (Connection connection : open) connection.close();
    }

    /** The node is closing for good: the waiting calls fail with RPC_NODE_CLOSED and dialing stops. */
    void close() {
        settleAll(null, RPC_NODE_CLOSED);
        stopDialing();
    }

    /** Blocks until the dialed connections are closed. */
    private void stopDialing() {
        for (NetworkClient client : clients) client.close();
    }

    @Override public String toString() {
        return "RpcPeer[" + nodeId + (outbound ? ", dialed" : ", dialing in") + ", " + connections.length + " connections]";
    }

    /**
     * A call waiting for its answer: the request without its body, the connection it went out on, its start. It is
     * its own timeout task, which fails it with RPC_TIMEOUT unless something settled it first.
     */
    private final class Pending implements TimerTask {
        final RpcRequest request;
        final RpcCallback callback;
        final Connection connection;
        final long start;
        volatile Timeout timeout;

        Pending(RpcRequest request, RpcCallback callback, Connection connection, long start) {
            this.request = request;
            this.callback = callback;
            this.connection = connection;
            this.start = start;
        }

        @Override public void run(Timeout expired) { settle(request.requestId(), this, RPC_TIMEOUT); }

        void complete(RpcResponse response) {
            Timeout current = timeout;
            if (current != null) current.cancel();
            node.settled(RpcPeer.this, request, response, System.nanoTime() - start);
            try {
                callback.onResponse(request, response);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "An RPC callback threw", e);
            }
        }
    }
}
