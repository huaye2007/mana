package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.NetworkClient;
import cn.managame.rpc.RpcCodec.Handshake;
import io.netty.util.AttributeKey;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Another node, known by its id, one to one with the process running it, whichever side dialed: one this node
 * dials ({@link RpcNode#createPeer}) or one that dials this node and joins when its handshake completes. Either
 * way every connection carries calls, notifies and answers both ways.
 *
 * <pre>{@code
 * peer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> ...);
 * peer.notify(RpcNotify.of(MAIL_ARRIVED, playerId, body));
 * }</pre>
 *
 * <p>A peer has as many connections as the dialing side opens, each in a slot of its own. A call or notify goes on
 * the connection of the slot its route key hashes to, so messages with one key reach the peer in the order they
 * were sent; while that connection is down they go on the next connection up, and when none is up a call fails
 * with RPC_UNAVAILABLE. A peer without connections stays known until it is removed, and the node dialing in again
 * rejoins the same instance.
 *
 * <p>Each running instance of a node has its own {@link #incarnation()}, so a restart is told from a reconnect.
 * Only one instance of a peer is connected at a time: a newer one replaces the connected one, whose connections
 * close and whose waiting calls fail, and an older one is refused while a newer one is connected.
 *
 * <p>The calls waiting for an answer are the peer's, under request ids of the peer's own, not any connection's:
 * an answer ends its call on whichever connection of the instance it comes, so a call outlives the connection it
 * went out on while another is up. When the last one closes, as when the peer's process crashed or stopped, the
 * waiting calls fail at once. Their timeouts end them too, and so do a new instance of the peer, its removal and
 * the close of the node.
 *
 * <p>A message handed to {@link #call} or {@link #notify(RpcNotify)} becomes the node's, which releases it once
 * sent and also when it cannot be sent, so the caller must not use it afterwards; to pass on a message received in
 * the handler, retain it first.
 *
 * <p>Calls, notifies and answers take no lock: they read the instance connected and its connections from one
 * immutable {@link Instance}, and the waiting calls are lock-free. Only connections joining and leaving lock the
 * peer, and the listener runs under that lock, so it hears one peer's changes in order and may still call or
 * notify any peer.
 */
public final class RpcPeer {
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    /** The peer a connection belongs to, once its handshake is done. */
    private static final AttributeKey<RpcPeer> KEY = AttributeKey.valueOf("cn.managame.rpc.peer");
    /** The incarnation of the instance of the peer a connection joined. */
    private static final AttributeKey<Long> INCARNATION = AttributeKey.valueOf("cn.managame.rpc.incarnation");
    private final RpcNode node;
    private final int nodeId;
    private final boolean outbound;
    private final PendingCalls pending;
    /** The dialers keeping the connections to an outbound peer up. */
    private volatile List<NetworkClient> clients = List.of();
    /** Replaced whole, under this, as connections join and leave. */
    private volatile Instance instance;
    private volatile Metadata metadata = Metadata.empty();
    private volatile boolean removed;
    /** Guarded by this: whether the instance connected was announced, and whether it is a restart. */
    private boolean announced, restarted;

    /**
     * The running instance of the peer connected, 0 before the first, and its connection in each slot, null while
     * it is down; as many slots as the dialing side opens connections. Never changed once published.
     */
    private record Instance(long incarnation, Connection[] slots) {}

    RpcPeer(RpcNode node, int nodeId, boolean outbound, int slots) {
        this.node = node;
        this.nodeId = nodeId;
        this.outbound = outbound;
        pending = new PendingCalls(node.maxPendingCalls());
        instance = new Instance(0, new Connection[slots]);
    }

    public int nodeId() { return nodeId; }

    /**
     * The running instance of the node last connected, which a restart changes; 0 before the first handshake. Newer
     * instances have larger incarnations as long as the clocks of the machines they start on roughly agree.
     */
    public long incarnation() { return instance.incarnation(); }

    /** What the peer sent in its latest handshake: whatever its application put there. Empty before the first. */
    public Metadata metadata() { return metadata; }

    /** True when this node dials the peer, false when the peer dials this node. */
    public boolean isOutbound() { return outbound; }

    /** Whether at least one connection is up, so that calls and notifies can go out. */
    public boolean isConnected() { return connectionCount() > 0; }

    public int connectionCount() {
        int up = 0;
        for (Connection connection : instance.slots()) if (connection != null) up++;
        return up;
    }

    /** The calls to the peer waiting for their answer. */
    public int pendingCount() { return pending.size(); }

    /** {@link #call(RpcRequest, Duration, RpcCallback)} with the node's timeout. */
    public void call(RpcRequest request, RpcCallback callback) { call(request, node.timeout(), callback); }

    /**
     * Sends {@code request} under a new request id and a deadline {@code timeout} away, on the connection its route
     * key picks, and hands the outcome to {@code callback}, exactly once and never on the calling thread. Nothing
     * about the network throws: the callback receives the callee's answer, which may be a failure code of the
     * callee's, or one of
     * <ul>
     * <li>RPC_UNAVAILABLE: no connection is up, the connection is too busy to take the request, as many calls as
     * the builder's {@code maxPendingCalls} wait already, the peer's last connection closed while the call waited,
     * a new instance of the peer replaced the one asked, or the peer was removed or this node closed;</li>
     * <li>RPC_TIMEOUT: no answer within {@code timeout}, also when the request or its answer was lost with a
     * connection that closed while another stayed up;</li>
     * <li>RPC_PROTOCOL_ERROR: the answer was larger than the callee's largest frame.</li>
     * </ul>
     *
     * @throws IllegalArgumentException for a timeout under a millisecond, or a request larger than this node's
     *                                  largest frame, after releasing the request
     */
    public void call(RpcRequest request, Duration timeout, RpcCallback callback) {
        Objects.requireNonNull(request, "request");
        long timeoutNanos;
        try {
            Objects.requireNonNull(callback, "callback");
            if (timeout.toMillis() < 1) throw new IllegalArgumentException("timeout must be at least one millisecond");
            if (!RpcCodec.fits(request, node.maxFrameLength()))
                throw new IllegalArgumentException("Request " + request.command() + " is larger than the largest frame, " + node.maxFrameLength() + " bytes");
            timeoutNanos = timeout.toNanos();
        } catch (RuntimeException e) {
            request.release();
            throw e;
        }
        Call call = new Call(pending, request, callback, System.nanoTime() + timeoutNanos);
        Connection connection = removed || node.isClosed() ? null : connection(request.routeKey());
        if (connection == null || !connection.isWritable() || !pending.add(call)) {
            call.release();
            failLater(call, RPC_UNAVAILABLE);
            return;
        }
        try {
            call.timeout = node.timer().newTimeout(call, timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (IllegalStateException stopped) {} // the node is closing, and fails the call
        // Read after joining: a removal or a close either fails the call or is seen here.
        if (!removed && !node.isClosed() && connection.write(call) == WriteStatus.ACCEPTED) return;
        call.release();
        if (pending.remove(call)) failLater(call, RPC_UNAVAILABLE);
    }

    /**
     * Sends {@code notify} on the connection its route key picks and returns what became of it, releasing it unless
     * ACCEPTED:
     * <ul>
     * <li>ACCEPTED: the connection took it, though it may still close before the notify leaves;</li>
     * <li>NOT_WRITABLE: the connection is too busy;</li>
     * <li>INACTIVE: no connection is up, the peer was removed, or this node closed.</li>
     * </ul>
     *
     * @throws IllegalArgumentException when the notify is larger than this node's largest frame, after releasing it
     */
    public WriteStatus notify(RpcNotify notify) {
        Objects.requireNonNull(notify, "notify");
        if (!RpcCodec.fits(notify, node.maxFrameLength())) {
            notify.release();
            throw new IllegalArgumentException("Notify " + notify.command() + " is larger than the largest frame, "
                    + node.maxFrameLength() + " bytes");
        }
        Connection connection = removed || node.isClosed() ? null : connection(notify.routeKey());
        if (connection != null) return RpcCodec.write(connection, notify);
        notify.release();
        return WriteStatus.INACTIVE;
    }

    /** The connection of the slot {@code routeKey} hashes to or, while that is down, the next one up; null for none. */
    private Connection connection(long routeKey) {
        Connection[] slots = instance.slots();
        int count = slots.length;
        int home = count < 2 ? 0 : Math.floorMod(routeKey, count);
        for (int i = 0; i < count; i++) {
            Connection connection = slots[home + i < count ? home + i : home + i - count];
            if (connection != null && connection.isActive()) return connection;
        }
        return null;
    }

    /** Fails {@code call} with {@code code} on an EventLoop its route key picks: never inside {@link #call}. */
    private void failLater(Call call, int code) { node.runOnEventLoop(call.request.routeKey(), () -> call.fail(code)); }

    /** The peer {@code connection} belongs to, or null before its handshake. */
    static RpcPeer of(Connection connection) { return connection.get(KEY); }

    /**
     * Writes {@code response} to {@code connection} or, once that has closed, to a connection up of the same
     * instance of the peer; see {@link RpcResponse#writeTo}.
     */
    static WriteStatus reply(Connection connection, RpcResponse response) {
        Objects.requireNonNull(response, "response");
        if (!connection.isActive() && of(connection) instanceof RpcPeer peer
                && connection.get(INCARNATION) instanceof Long incarnation && incarnation == peer.instance.incarnation()) {
            Connection other = peer.connection(response.requestId());
            if (other != null) return RpcCodec.writeEvenIfBusy(other, response);
        }
        return RpcCodec.writeEvenIfBusy(connection, response);
    }

    /** An answer from the peer: it ends its call, if that still waits. */
    void answer(RpcResponse response) {
        Call call = pending.remove(response.requestId());
        if (call != null) call.complete(response);
    }

    /** The connections the dialing side opens: fixed for a peer this node dials. */
    int slotCount() { return instance.slots().length; }

    void dial(List<NetworkClient> clients) { this.clients = List.copyOf(clients); }

    /**
     * Adds a connection that completed its handshake with an instance of the peer, in {@code slot}, unless refused:
     * when the peer was removed or the node closed, an older instance dials while a newer one is connected, or the
     * connections of one instance disagree on their number. A newer instance than the one known replaces it: the
     * old one's connections close and its waiting calls fail, as it cannot answer them. {@code beforeJoining} runs
     * once the connection is accepted and before anyone can use it. Returns why the connection was refused, or null.
     */
    String addConnection(Connection connection, Handshake theirs, int slot, Runnable beforeJoining) {
        List<Connection> dropped = new ArrayList<>(0);
        boolean replaced = false;
        synchronized (this) {
            if (removed || node.isClosed()) return "node " + nodeId + " was removed, or this node closed";
            Instance current = instance;
            if (theirs.incarnation() != current.incarnation()) {
                for (Connection old : current.slots()) if (old != null) dropped.add(old);
                if (!dropped.isEmpty() && theirs.incarnation() < current.incarnation())
                    return "an older instance of node " + nodeId + " dials while a newer one is connected";
                if (current.incarnation() != 0) {
                    if (!dropped.isEmpty()) LOG.log(Level.WARNING, "A new instance of node " + nodeId + " replaces the connected one");
                    replaced = restarted = true;
                }
                current = new Instance(theirs.incarnation(), new Connection[outbound ? current.slots().length : theirs.slots()]);
                instance = current;
                if (announced) {
                    announced = false;
                    node.fireDisconnected(this);
                }
            } else if (!outbound && theirs.slots() != current.slots().length) {
                return "node " + nodeId + " opens " + theirs.slots() + " connections, after " + current.slots().length;
            }
            metadata = theirs.metadata();
            beforeJoining.run();
            connection.set(INCARNATION, current.incarnation());
            connection.set(KEY, this);
            Connection[] slots = current.slots().clone();
            if (slots[slot] != null) dropped.add(slots[slot]); // the slot dialed again while its old connection lingers
            slots[slot] = connection;
            instance = new Instance(current.incarnation(), slots);
            if (!announced && connectionCount() == slots.length) {
                announced = true;
                boolean restart = restarted;
                restarted = false;
                node.fireConnected(this, restart);
            }
        }
        for (Connection old : dropped) old.close();
        if (replaced) pending.failAll(RPC_UNAVAILABLE);
        return null;
    }

    /**
     * Removes a connection that closed. The calls sent on it keep waiting while another connection is up, as their
     * answers may come on it; once none is, every waiting call fails with RPC_UNAVAILABLE.
     */
    void removeConnection(Connection connection) {
        boolean last = false;
        synchronized (this) {
            Instance current = instance;
            Connection[] slots = current.slots();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != connection) continue;
                slots = slots.clone();
                slots[i] = null;
                instance = new Instance(current.incarnation(), slots);
                last = connectionCount() == 0;
                if (last && announced) {
                    announced = false;
                    node.fireDisconnected(this);
                }
                break;
            }
        }
        if (last) pending.failAll(RPC_UNAVAILABLE);
    }

    /** Fails the waiting calls with RPC_UNAVAILABLE, then stops dialing and closes every connection. */
    void remove() {
        Connection[] slots;
        synchronized (this) {
            removed = true;
            slots = instance.slots();
        }
        pending.failAll(RPC_UNAVAILABLE);
        for (NetworkClient client : clients) client.close();
        for (Connection connection : slots) if (connection != null) connection.close();
    }

    /** The node is closing: the waiting calls fail with RPC_UNAVAILABLE and dialing stops. */
    void close() {
        pending.failAll(RPC_UNAVAILABLE);
        for (NetworkClient client : clients) client.close();
    }

    @Override public String toString() {
        return "RpcPeer[" + nodeId + (outbound ? ", dialed" : ", dialing in") + ", " + connectionCount() + " connections]";
    }
}
