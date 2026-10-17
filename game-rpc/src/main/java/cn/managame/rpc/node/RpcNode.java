package cn.managame.rpc.node;

import static cn.managame.rpc.error.RpcErrorCodes.HANDLER_ERROR;
import static cn.managame.rpc.error.RpcErrorCodes.NODE_CLOSED;
import static cn.managame.rpc.error.RpcErrorCodes.PEER_NOT_FOUND;
import static cn.managame.rpc.error.RpcErrorCodes.PEER_REMOVED;
import static cn.managame.rpc.error.RpcErrorCodes.PROTOCOL_ERROR;
import static cn.managame.rpc.error.RpcErrorCodes.TIMEOUT;
import static cn.managame.rpc.error.RpcErrorCodes.UNAVAILABLE;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.connector.ConnectCallback;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkClientBuilder;
import cn.managame.network.netty.NetworkServer;
import cn.managame.network.netty.NetworkServerBuilder;
import cn.managame.network.netty.NetworkTransports;
import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.call.RpcHandler;
import cn.managame.rpc.error.RpcException;
import cn.managame.rpc.message.RpcHandshake;
import cn.managame.rpc.message.RpcRequest;
import cn.managame.rpc.message.RpcResponse;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * TCP RPC endpoint with peer-scoped calls and fixed connection slots.
 * start/close are blocking management operations, forbidden inside this node's handlers,
 * EventLoops and timer. Sending does not acquire the topology lifecycle lock.
 *
 * <p>Threads: requests, responses and connection-detected failures reach the RpcHandler on Netty EventLoops;
 * immediate send failures on the caller; removal and closure on the management thread; call timeouts on
 * this Node's timer thread. The handler must hand longer work to its own executor (for example a Runtime
 * Route), because a blocked timer thread delays later timeouts, handshake timeouts and reconnects.
 */
public final class RpcNode implements AutoCloseable {
    private enum State { NEW, RUNNING, CLOSED }
    private static final AttributeKey<RpcConnectionContext> CONTEXT =
            AttributeKey.valueOf(RpcNode.class, "context");
    private static final System.Logger LOG = System.getLogger(RpcNode.class.getName());
    /** How long close waits for connections on a caller-owned EventLoopGroup to finish. */
    private static final long EXTERNAL_CLOSE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final int nodeId, maxFrameSize, maxPendingCalls, maxInboundPeers, maxConnections;
    private final SocketAddress bindAddress;
    private final RpcHandler handler;
    private final long callTimeout, handshakeTimeout, reconnectDelay, heartbeatInterval, heartbeatTimeout;
    private final long reconnectRandomDelay;
    private final Map<ChannelOption<?>, Object> channelOptions;
    private final List<Consumer<ChannelPipeline>> transports;
    private final HandshakeGuard guard;
    private final EventLoopGroup externalGroup;
    private final Object lifecycleLock = new Object();
    /** Written only under lifecycleLock. */
    final PeerTable peers = new PeerTable();
    // Call IDs are positive and wrap from Integer.MAX_VALUE back to 1. The start is random, so a restarted
    // Node practically never reissues the IDs a peer still holds for the previous incarnation (R-CALL-04).
    final AtomicInteger requestIds = new AtomicInteger(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE) + 1);
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    // Close barrier for admitted API work, including late pending registrations. Striped, so concurrent
    // senders do not contend on one word, and unbounded (Phaser allows at most 65535 parties).
    private final LongAdder operations = new LongAdder();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final ThreadLocal<Boolean> inHandler = ThreadLocal.withInitial(() -> false);
    private volatile State state = State.NEW;
    private volatile Thread closingThread, timerThread;
    private EventLoopGroup boss, workers;
    private NetworkServer server;
    private NetworkClient client;
    private HashedWheelTimer timer;

    RpcNode(RpcNodeBuilder b) {
        nodeId = b.nodeId; bindAddress = b.address; handler = b.handler; maxFrameSize = b.maxFrameSize;
        maxPendingCalls = b.maxPendingCalls;
        maxInboundPeers = b.maxInboundPeers;
        maxConnections = b.maxConnections;
        callTimeout = b.callTimeout; handshakeTimeout = b.handshakeTimeout; reconnectDelay = b.reconnectDelay;
        heartbeatInterval = b.heartbeatInterval; heartbeatTimeout = b.heartbeatTimeout;
        reconnectRandomDelay = b.reconnectRandomDelay < 0 ? reconnectDelay / 4 : b.reconnectRandomDelay;
        channelOptions = Map.copyOf(b.channelOptions); transports = List.copyOf(b.transports);
        guard = new HandshakeGuard(b.handshakeSecret, b.handshakeClockSkew);
        externalGroup = b.eventLoopGroup;
    }

    public static RpcNodeBuilder builder() { return new RpcNodeBuilder(); }

    public int nodeId() { return nodeId; }

    /** Largest frame this Node encodes or accepts, length prefix included. */
    public int maxFrameSize() { return maxFrameSize; }

    /** Snapshot only: at least one bound connection is active; does not promise delivery. */
    public boolean isPeerConnected(int remoteNodeId) {
        RpcPeer peer = peers.get(remoteNodeId);
        return state == State.RUNNING && peer != null && peer.connected();
    }

    /** Configured Slot count, or zero for an absent Peer. */
    public int peerSlotCount(int remoteNodeId) {
        RpcPeer peer = peers.get(remoteNodeId);
        return peer == null ? 0 : peer.slots.length;
    }

    public SocketAddress localAddress() {
        synchronized (lifecycleLock) { return server == null ? null : server.localAddress(); }
    }

    public void start() {
        checkManagementThread();
        RuntimeException failure = null;
        synchronized (lifecycleLock) {
            if (state != State.NEW) throw new IllegalStateException("RPC node already started or closed");
            try {
                timer = new HashedWheelTimer(task -> {
                    Thread thread = new Thread(task, "rpc-" + Integer.toUnsignedString(nodeId) + "-timer");
                    thread.setDaemon(true);
                    timerThread = thread;
                    return thread;
                }, 10, TimeUnit.MILLISECONDS);
                timer.start();
                if (externalGroup != null) {
                    boss = workers = externalGroup;
                } else {
                    boss = NetworkTransports.newEventLoopGroup(1);
                    workers = NetworkTransports.newEventLoopGroup(0);
                }
                RpcConnectionHandler bridge = new RpcConnectionHandler(this);
                Consumer<ChannelPipeline> pipeline = p -> {
                    for (var transport : transports) transport.accept(p);   // byte-level handlers first
                    // Coalesce flushes from concurrent senders into one syscall per EventLoop turn.
                    p.addLast("rpc-flush", new FlushConsolidationHandler(256, true));
                    RpcWire.configurePipeline(p, maxFrameSize, heartbeatInterval, heartbeatTimeout);
                };
                var clientBuilder = NetworkClient.builder().eventLoopGroup(workers).handler(bridge).pipeline(pipeline);
                var serverBuilder = NetworkServer.builder().bindAddress(bindAddress).bossGroup(boss).workerGroup(workers)
                        .handler(bridge).pipeline(pipeline);
                channelOptions.forEach((option, value) -> applyOption(clientBuilder, serverBuilder, option, value));
                client = clientBuilder.build();
                server = serverBuilder.build();
                server.start();
                state = State.RUNNING;
            } catch (RuntimeException error) {
                state = State.CLOSED;
                closingThread = Thread.currentThread();
                failure = error;
            }
        }
        if (failure != null) {
            try { closeResources(); }
            finally { closed.countDown(); }
            throw new RpcException("RPC node start failed", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void applyOption(NetworkClientBuilder client, NetworkServerBuilder server,
                                        ChannelOption<T> option, Object value) {
        client.option(option, (T) value);
        server.childOption(option, (T) value);
    }

    /**
     * Declares this node as the dialing side for the remote node and immediately maintains every empty
     * slot. Idempotent for equal configuration. Exactly one side of a pair calls addPeer; the other side
     * learns the peer from its inbound handshake. Fails if the remote node is already connected inbound.
     */
    public void addPeer(int remoteNodeId, SocketAddress address, int slotCount) {
        validateTarget(remoteNodeId);
        Objects.requireNonNull(address, "address");
        if (slotCount < 1 || slotCount > 255) throw new IllegalArgumentException("slotCount must be 1..255");
        RpcPeer peer;
        synchronized (lifecycleLock) {
            ensureRunning();
            peer = peers.get(remoteNodeId);
            if (peer == null) {
                peer = new RpcPeer(remoteNodeId, slotCount, address);
                peers.put(remoteNodeId, peer);
            } else if (!peer.outbound) {
                throw new IllegalStateException("Peer " + Integer.toUnsignedString(remoteNodeId)
                        + " already dialed this node; only one side of a pair may call addPeer");
            } else if (peer.slots.length != slotCount) {
                throw new IllegalStateException("Peer slotCount differs; remove it first");
            } else if (!peer.target.equals(address)) {
                throw new IllegalStateException("Peer address differs; remove it first");
            }
        }
        for (ConnectionSlot slot : peer.slots) {
            if (slot.connection.get() == null && slot.connecting.compareAndSet(false, true)) connect(peer, slot);
        }
    }

    public void removePeer(int remoteNodeId) {
        validateTarget(remoteNodeId);
        enterOperation();
        try {
            RpcPeer peer;
            synchronized (lifecycleLock) {
                peer = peers.remove(remoteNodeId);
            }
            if (peer != null) detach(peer, PEER_REMOVED);
        } finally { operations.decrement(); }
    }

    /**
     * After argument and lifecycle validation, consumes one body reference on every path.
     * Encoding errors throw synchronously. Runtime availability failures go to RpcHandler.onFail.
     */
    public <T> void call(int remoteNodeId, RpcRequest request, RpcCallback<T> callback) {
        call(remoteNodeId, request, callTimeout, callback);
    }

    public <T> void call(int remoteNodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback) {
        validateRequest(remoteNodeId, request);
        Objects.requireNonNull(callback, "callback");
        if (timeoutMillis <= 0 || timeoutMillis > Long.MAX_VALUE / 1_000_000)
            throw new IllegalArgumentException("timeout must fit positive nanoseconds");
        enterOperation();
        ByteBuf body = request.body(), frame = null;
        RpcPeer peer = null;
        PendingCall pending = null;
        boolean reserved = false; // a pending place is held but not yet owned by a registered call
        try {
            peer = peers.get(remoteNodeId);
            if (peer == null) {
                ReferenceCountUtil.release(body); body = null;
                fireFail(remoteNodeId, request.command(), state == State.RUNNING ? PEER_NOT_FOUND : NODE_CLOSED, callback);
                return;
            }
            if (!peer.reservePending(maxPendingCalls)) {
                ReferenceCountUtil.release(body); body = null;
                fireFail(remoteNodeId, request.command(), UNAVAILABLE, callback);
                return;
            }
            reserved = true;
            int id = nextRequestId();
            body = null; // RpcWire consumes it, including exceptions.
            frame = RpcWire.encodeRequest(request, id, maxFrameSize);
            pending = new PendingCall(id, request.command(), callback);
            if (!peer.addPending(pending)) {
                pending = null;
                throw new RpcException("requestId collided with an outstanding call");
            }
            reserved = false;
            if (!current(peer)) {
                fail(peer, pending, state == State.RUNNING ? PEER_REMOVED : NODE_CLOSED);
                return;
            }
            ByteBuf sending = frame; frame = null; // send consumes the complete frame.
            if (send(peer, -1, request.routeKey(), sending) != RpcSendStatus.ACCEPTED) {
                fail(peer, pending, state != State.RUNNING ? NODE_CLOSED : peers.get(remoteNodeId) != peer ? PEER_REMOVED : UNAVAILABLE);
                return;
            }
            RpcPeer scheduledPeer = peer;
            PendingCall scheduledCall = pending;
            Timeout timeout = timer.newTimeout(ignored -> timeout(scheduledPeer, scheduledCall),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            pending.timeout = timeout;
            // A response/removal may have completed before timeout publication.
            if (peer.pending.get(id) != pending) timeout.cancel();
        } catch (RuntimeException | Error error) {
            // Encoding/collision/write exceptions are synchronous, never a second completion.
            if (reserved) peer.releasePending();
            if (peer != null && pending != null && peer.removePending(pending)) {
                pending.cancelTimeout();
                tryRemovePassive(peer);
            }
            throw error;
        } finally {
            ReferenceCountUtil.release(body);
            ReferenceCountUtil.release(frame);
            operations.decrement();
        }
    }

    /** Consumes the body after argument/lifecycle validation, including PEER_NOT_FOUND and UNAVAILABLE. */
    public RpcSendStatus notify(int remoteNodeId, RpcRequest request) {
        validateRequest(remoteNodeId, request);
        enterOperation();
        ByteBuf body = request.body();
        try {
            RpcPeer peer = peers.get(remoteNodeId);
            if (peer == null) return RpcSendStatus.PEER_NOT_FOUND;
            body = null;
            return send(peer, -1, request.routeKey(), RpcWire.encodeRequest(request, 0, maxFrameSize));
        } finally {
            ReferenceCountUtil.release(body);
            operations.decrement();
        }
    }

    /**
     * Delivers a response this Node did not produce, for a relay such as a Router: it never saw the connection
     * the call went out on, so the response takes any writable Slot of the target like a notify. An application
     * answering a request it received itself uses {@link #reply} instead. Consumes the body.
     */
    public RpcSendStatus forwardResponse(int remoteNodeId, long routeKey, RpcResponse response) {
        validateTarget(remoteNodeId);
        Objects.requireNonNull(response, "response");
        enterOperation();
        ByteBuf body = response.body();
        try {
            RpcPeer peer = peers.get(remoteNodeId);
            if (peer == null) return RpcSendStatus.PEER_NOT_FOUND;
            body = null;
            return send(peer, -1, routeKey, RpcWire.encodeResponse(response, maxFrameSize));
        } finally {
            ReferenceCountUtil.release(body);
            operations.decrement();
        }
    }

    /**
     * Answers on the request's own Slot only; UNAVAILABLE when that connection is gone or unwritable, never a
     * different Slot. Consumes the body.
     */
    public RpcSendStatus reply(int remoteNodeId, int sourceSlotId, RpcResponse response) {
        validateTarget(remoteNodeId);
        Objects.requireNonNull(response, "response");
        if (sourceSlotId < 0 || sourceSlotId >= 255) throw new IllegalArgumentException("invalid sourceSlotId");
        enterOperation();
        ByteBuf body = null;
        try {
            RpcPeer peer = peers.get(remoteNodeId);
            if (peer != null && sourceSlotId >= peer.slots.length)
                throw new IllegalArgumentException("sourceSlotId outside peer");
            body = response.body(); // Pure argument checks are complete.
            if (peer == null) return RpcSendStatus.PEER_NOT_FOUND;
            body = null;
            return answer(peer, sourceSlotId, RpcWire.encodeResponse(response, maxFrameSize));
        } finally {
            ReferenceCountUtil.release(body);
            operations.decrement();
        }
    }

    /**
     * One pass over the Slots for an outbound request: the preferred one, then from the routeKey start. Always
     * consumes the frame; a rejected attempt keeps it for the next Slot and ACCEPTED ends the traversal.
     */
    RpcSendStatus send(RpcPeer peer, int preferred, long routeKey, ByteBuf frame) {
        boolean accepted = false;
        try {
            if (!current(peer)) return RpcSendStatus.UNAVAILABLE;
            if (preferred >= 0 && tryWrite(peer.slots[preferred], frame)) {
                accepted = true;
                return RpcSendStatus.ACCEPTED;
            }
            int count = peer.slots.length, start = peer.startSlot(routeKey);
            for (int i = 0; i < count; i++) {
                int index = start + i < count ? start + i : start + i - count;
                if (index == preferred) continue;
                if (i > 0 && !current(peer)) break;
                if (tryWrite(peer.slots[index], frame)) {
                    accepted = true;
                    return RpcSendStatus.ACCEPTED;
                }
            }
            return RpcSendStatus.UNAVAILABLE;
        } finally { if (!accepted) frame.release(); }
    }

    /**
     * Answers a request on the Slot that carried it, with no fallback: a response never crosses to another
     * connection, so it cannot reach a different incarnation of the caller and complete an unrelated call.
     * An unwritable Slot therefore refuses the response instead of rerouting it. Always consumes the frame.
     */
    private RpcSendStatus answer(RpcPeer peer, int sourceSlotId, ByteBuf frame) {
        boolean accepted = false;
        try {
            if (!current(peer)) return RpcSendStatus.UNAVAILABLE;
            accepted = tryWrite(peer.slots[sourceSlotId], frame);
            return accepted ? RpcSendStatus.ACCEPTED : RpcSendStatus.UNAVAILABLE;
        } finally { if (!accepted) frame.release(); }
    }

    private boolean tryWrite(ConnectionSlot slot, ByteBuf frame) {
        Connection connection = slot.connection.get();
        if (connection == null || !connection.isActive() || !connection.isWritable()) return false;
        try { return connection.write(frame) == WriteStatus.ACCEPTED; }
        catch (RuntimeException error) { connection.close(); throw error; }
    }

    // ---------------------------------------------------------------- outbound connection recovery

    private void connect(RpcPeer peer, ConnectionSlot slot) {
        if (!continueRecovery(peer, slot)) return;
        client.connectAsync(peer.target, new ConnectCallback() {
            public void onSuccess(Connection connection) {
                RpcConnectionContext context = connection.get(CONTEXT);
                boolean associated;
                synchronized (lifecycleLock) {
                    associated = current(peer) && peer.recovering() && context != null
                            && !context.handshakeFinished.get() && connection.isActive();
                    if (associated) {
                        context.expectedPeer = peer;
                        context.expectedSlot = slot;
                    }
                }
                if (!associated) {
                    connection.close();
                    reconnect(peer, slot);
                    return;
                }
                writeControl(connection, guard.initiate(context, new RpcHandshake(nodeId, slot.id, peer.slots.length)));
            }
            public void onFailure(Throwable cause) { reconnect(peer, slot); }
        });
    }

    private void reconnect(RpcPeer peer, ConnectionSlot slot) {
        if (!continueRecovery(peer, slot)) return;
        try {
            timer.newTimeout(ignored -> connect(peer, slot), nextReconnectDelayMillis(), TimeUnit.MILLISECONDS);
        } catch (IllegalStateException stopped) {
            slot.connecting.set(false);
            if (state == State.RUNNING) throw stopped;
        }
    }

    // Relinquish the chain, then recheck: an unbind may have seen connecting=true
    // before the clear. Either this chain reacquires ownership or the unbinder does.
    boolean continueRecovery(RpcPeer peer, ConnectionSlot slot) {
        if (!current(peer)) {
            slot.connecting.set(false);
            return false;
        }
        if (peer.recovering() && slot.connection.get() == null) return true;
        slot.connecting.set(false);
        return current(peer) && peer.recovering() && slot.connection.get() == null
                && slot.connecting.compareAndSet(false, true);
    }

    long nextReconnectDelayMillis() {
        return reconnectDelay + (reconnectRandomDelay == 0 ? 0 : ThreadLocalRandom.current().nextLong(reconnectRandomDelay + 1));
    }

    // ---------------------------------------------------------------- connection events (EventLoops)

    void connected(Connection connection) {
        synchronized (lifecycleLock) {
            if (state != State.RUNNING) { connection.close(); return; }
            if (connections.size() >= maxConnections) {
                // Bounds unauthenticated inbound connections: a dial storm or a scanner cannot grow memory
                // while the handshake timeout has not yet closed the connections it opened.
                warn("Refused a connection from " + connection.remoteAddress() + ": maxConnections ("
                        + maxConnections + ") reached", null);
                connection.close();
                return;
            }
            RpcConnectionContext context = new RpcConnectionContext();
            connection.set(CONTEXT, context);
            connections.add(connection);
            context.timeout = timer.newTimeout(ignored -> {
                if (context.handshakeFinished.compareAndSet(false, true)) connection.close();
            }, handshakeTimeout, TimeUnit.MILLISECONDS);
            if (context.handshakeFinished.get()) context.timeout.cancel();
        }
    }

    void receive(Connection connection, Object message) {
        RpcConnectionContext context = connection.get(CONTEXT);
        if (state != State.RUNNING || context == null) { connection.close(); return; }
        try {
            if (!(message instanceof ByteBuf frame) || !frame.isReadable())
                throw new CorruptedFrameException("Empty/non-buffer RPC frame");
            int type = frame.readUnsignedByte();
            if (type == RpcWire.HANDSHAKE) {
                handshake(connection, context, guard.decode(frame));
                return;
            }
            if (context.slot == null || !current(context.peer))
                throw new CorruptedFrameException("RPC traffic before handshake or after peer removal");
            switch (type) {
                case RpcWire.HEARTBEAT -> {
                    if (frame.isReadable()) throw new CorruptedFrameException("Heartbeat payload");
                }
                case RpcWire.REQUEST -> fireRequest(context.peer, context.slot.id, RpcWire.decodeRequest(frame));
                case RpcWire.RESPONSE -> response(context.peer, frame);
                default -> throw new CorruptedFrameException("Unknown RPC message type: " + type);
            }
        } catch (RuntimeException error) {
            RpcPeer peer = context.peer;
            warn("Invalid RPC frame from " + (peer == null ? "unidentified peer" : "node " + Integer.toUnsignedString(peer.nodeId))
                    + " at " + connection.remoteAddress(), error);
            connection.close();
        }
    }

    private void handshake(Connection connection, RpcConnectionContext context, RpcWire.AuthenticatedHandshake received) {
        RpcHandshake hello = received.identity();
        if (hello.nodeId() == nodeId || context.slot != null || !context.handshakeFinished.compareAndSet(false, true))
            throw new CorruptedFrameException("Duplicate, expired or self handshake");
        guard.verify(context, received.proof());
        context.timeout.cancel();
        synchronized (lifecycleLock) {
            if (state != State.RUNNING || !connection.isActive()) { connection.close(); return; }
            if (context.expectedPeer != null) bindOutbound(connection, context, hello);
            else bindInbound(connection, context, hello, received.proof());
        }
    }

    /** Reply to our own dial. Caller holds lifecycleLock. */
    private void bindOutbound(Connection connection, RpcConnectionContext context, RpcHandshake hello) {
        RpcPeer peer = context.expectedPeer;
        ConnectionSlot slot = context.expectedSlot;
        if (!current(peer) || peer.nodeId != hello.nodeId() || slot.id != hello.slotId()
                || peer.slots.length != hello.slotCount())
            throw new CorruptedFrameException("Unexpected handshake identity");
        if (!slot.connection.compareAndSet(null, connection))
            throw new CorruptedFrameException("Duplicate slot");
        context.peer = peer;
        context.slot = slot;
        slot.connecting.set(false);
    }

    /**
     * An initiating handshake from a remote dialer. Caller holds lifecycleLock, which also serializes
     * passive-Peer removal, so a new Peer is published only after its Slot is bound.
     */
    private void bindInbound(Connection connection, RpcConnectionContext context, RpcHandshake hello,
                             RpcWire.HandshakeProof proof) {
        RpcPeer existing = peers.get(hello.nodeId());
        if (existing != null && existing.outbound)
            throw new CorruptedFrameException("Both nodes called addPeer for each other (remote "
                    + Integer.toUnsignedString(hello.nodeId()) + "); only one side of a pair may dial");
        if (existing == null && peers.inboundCount() >= maxInboundPeers) {
            // A Peer learned from a handshake costs a Slot array and up to maxPendingCalls outstanding calls,
            // so the number of Nodes that may dial this one is bounded. Configured Peers are not counted.
            warn("Refused an inbound Peer " + Integer.toUnsignedString(hello.nodeId()) + " from "
                    + connection.remoteAddress() + ": maxInboundPeers (" + maxInboundPeers + ") reached", null);
            connection.close();
            return;
        }
        RpcPeer peer = existing != null ? existing : new RpcPeer(hello.nodeId(), hello.slotCount(), null);
        if (peer.slots.length != hello.slotCount()) throw new CorruptedFrameException("Peer slotCount mismatch");
        ConnectionSlot slot = peer.slots[hello.slotId()];
        if (slot.connection.get() != null) throw new CorruptedFrameException("Duplicate slot");
        // A READY connection is not published until its reply handshake is admitted (writeControl closes on refusal).
        if (!writeControl(connection, guard.reply(new RpcHandshake(nodeId, slot.id, peer.slots.length), proof))) return;
        if (!connection.isActive() || !slot.connection.compareAndSet(null, connection)) {
            connection.close();
            return;
        }
        if (existing == null) peers.put(peer.nodeId, peer);
        context.peer = peer;
        context.slot = slot;
        // An existing outbound recovery chain stops itself on observing this binding.
    }

    private void response(RpcPeer peer, ByteBuf frame) {
        if (frame.readableBytes() < 4) throw new CorruptedFrameException("Response lacks requestId");
        int id = frame.readInt();
        if (id <= 0) throw new CorruptedFrameException("Non-positive response requestId");
        PendingCall pending = peer.removePending(id);
        if (pending == null) return;
        pending.cancelTimeout();
        try {
            RpcResponse response;
            try { response = RpcWire.decodeResponse(id, frame); }
            catch (RuntimeException badFrame) {
                fireFail(peer.nodeId, pending.command, PROTOCOL_ERROR, pending.callback);
                throw badFrame;
            }
            invoke(() -> handler.onResponse(peer.nodeId, pending.command, response, pending.callback));
        } finally { tryRemovePassive(peer); }
    }

    void disconnected(Connection connection) {
        connections.remove(connection);
        RpcConnectionContext context = connection.remove(CONTEXT);
        if (context == null || context.disconnected) return;
        context.disconnected = true;
        context.cancelHandshake();
        if (context.slot != null) {
            RpcPeer peer = context.peer;
            if (context.slot.connection.compareAndSet(connection, null)) {
                // Without any connection no response can arrive: fail now instead of waiting for timeouts.
                // A remaining Slot can still carry the response, so partial loss keeps pending calls.
                if (!peer.bound()) for (PendingCall call : peer.pending.values()) fail(peer, call, UNAVAILABLE);
                if (current(peer) && peer.recovering()
                        && context.slot.connecting.compareAndSet(false, true)) reconnect(peer, context.slot);
                tryRemovePassive(peer);
            }
        } else if (context.expectedSlot != null) {
            reconnect(context.expectedPeer, context.expectedSlot);
        }
    }

    void idle(Connection connection, IdleState idle) {
        RpcConnectionContext context = connection.get(CONTEXT);
        if (context == null || context.slot == null || !current(context.peer)) return;
        if (idle == IdleState.READER_IDLE) connection.close();
        else if (idle == IdleState.WRITER_IDLE) writeControl(connection, RpcWire.encodeHeartbeat());
    }

    private boolean writeControl(Connection connection, ByteBuf frame) {
        boolean accepted = false;
        try {
            accepted = connection.write(frame) == WriteStatus.ACCEPTED;
            return accepted;
        } finally {
            if (!accepted) { frame.release(); connection.close(); }
        }
    }

    // ---------------------------------------------------------------- handler notification

    private void fireRequest(RpcPeer peer, int slotId, RpcRequest request) {
        boolean previous = inHandler.get();
        inHandler.set(true);
        try { handler.onRequest(peer.nodeId, slotId, request); }
        catch (RuntimeException error) {
            warn("RpcHandler.onRequest failed for command " + request.command() + " from node "
                    + Integer.toUnsignedString(peer.nodeId), error);
            if (request.requestId() != 0 && current(peer)) {
                RpcResponse response = new RpcResponse(request.requestId(), HANDLER_ERROR, null, null);
                try {
                    RpcSendStatus status = answer(peer, slotId, RpcWire.encodeResponse(response, maxFrameSize));
                    if (status != RpcSendStatus.ACCEPTED) warn("Handler error response was not admitted", null);
                } catch (RuntimeException sendError) { warn("Handler error response failed", sendError); }
            }
        } finally { inHandler.set(previous); }
    }

    private void fireFail(int remoteNodeId, int command, int error, RpcCallback<?> callback) {
        invoke(() -> handler.onFail(remoteNodeId, command, error, callback));
    }

    private void invoke(Runnable action) {
        boolean previous = inHandler.get();
        inHandler.set(true);
        try { action.run(); }
        catch (RuntimeException error) { warn("RPC handler failed", error); }
        finally { inHandler.set(previous); }
    }

    private void fail(RpcPeer peer, PendingCall pending, int error) {
        if (!peer.removePending(pending)) return;
        pending.cancelTimeout();
        try { fireFail(peer.nodeId, pending.command, error, pending.callback); }
        finally { tryRemovePassive(peer); }
    }

    /** The next positive call ID; allocation wraps from Integer.MAX_VALUE back to 1 and never yields 0. */
    private int nextRequestId() {
        return requestIds.getAndUpdate(id -> id == Integer.MAX_VALUE ? 1 : id + 1);
    }

    /** Runs on the timer thread; the application hands the failure to its own executor if needed. */
    private void timeout(RpcPeer peer, PendingCall pending) {
        if (!peer.removePending(pending)) return;
        pending.cancelTimeout();
        tryRemovePassive(peer);
        fireFail(peer.nodeId, pending.command, TIMEOUT, pending.callback);
    }

    /** Drops an inbound Peer that has no connection and no outstanding call. Cheap when it does not apply. */
    private void tryRemovePassive(RpcPeer peer) {
        if (peer.outbound || !peer.pending.isEmpty() || peer.bound()) return;
        synchronized (lifecycleLock) {
            if (peer.pending.isEmpty() && !peer.bound()) peers.remove(peer.nodeId, peer);
        }
    }

    private void detach(RpcPeer peer, int error) {
        peer.detached = true;
        // Also close unfinished handshakes belonging to this peer, not just READY slots.
        for (Connection connection : connections) {
            RpcConnectionContext context = connection.get(CONTEXT);
            if (context != null && (context.peer == peer || context.expectedPeer == peer)) connection.close();
        }
        try {
            for (PendingCall call : peer.pending.values()) fail(peer, call, error);
        } finally {
            for (ConnectionSlot slot : peer.slots) {
                Connection connection = slot.connection.getAndSet(null);
                if (connection != null) connection.close();
                slot.connecting.set(false);
            }
        }
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Idempotent synchronous barrier. All admitted sends, callbacks on this Node's EventLoops, connections
     * and timers finish before return. Does not wait for application work dispatched elsewhere.
     */
    @Override public void close() {
        if (Thread.currentThread() == closingThread) return; // reentrant shutdown failure handler
        checkManagementThread();
        List<RpcPeer> closing = null;
        synchronized (lifecycleLock) {
            if (state != State.CLOSED) {
                state = State.CLOSED;
                closingThread = Thread.currentThread();
                closing = peers.clear();
            }
        }
        if (closing == null) { awaitClosed(); return; }
        try {
            awaitOperations();
            for (RpcPeer peer : closing) detach(peer, NODE_CLOSED);
        } finally {
            try { closeResources(); }
            finally { closed.countDown(); }
        }
    }

    private void closeResources() {
        for (Connection connection : connections) connection.close();
        cleanup(() -> { if (server != null) server.close(); });
        cleanup(() -> { if (client != null) client.close(); });
        if (externalGroup == null) {
            cleanup(() -> { if (boss != null) boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly(); });
            cleanup(() -> { if (workers != null) workers.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly(); });
        } else {
            awaitConnectionsClosed();
        }
        cleanup(() -> { if (timer != null) timer.stop(); });
        connections.clear();
    }

    /** With a caller-owned group, waits (bounded) until every connection reported its disconnection. */
    private void awaitConnectionsClosed() {
        long deadline = System.nanoTime() + EXTERNAL_CLOSE_WAIT_NANOS;
        while (!connections.isEmpty() && System.nanoTime() - deadline < 0) LockSupport.parkNanos(1_000_000);
        if (!connections.isEmpty())
            warn(connections.size() + " RPC connections were still closing when close returned", null);
    }

    private void cleanup(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { warn("RPC shutdown resource failed", error); }
    }

    private void awaitClosed() {
        boolean interrupted = false;
        while (true) {
            try { closed.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Admitted operations are short and nonblocking apart from application failure callbacks. */
    private void awaitOperations() {
        while (operations.sum() != 0) LockSupport.parkNanos(50_000);
    }

    private void enterOperation() {
        ensureRunning();
        operations.increment();
        if (state != State.RUNNING) {
            operations.decrement();
            throw new IllegalStateException("RPC node is not running");
        }
    }

    private void ensureRunning() {
        if (state != State.RUNNING) throw new IllegalStateException("RPC node is not running");
    }

    private boolean current(RpcPeer peer) {
        return state == State.RUNNING && peers.get(peer.nodeId) == peer;
    }

    private void validateTarget(int remoteNodeId) {
        if (remoteNodeId == 0 || remoteNodeId == nodeId)
            throw new IllegalArgumentException("remote nodeId must be nonzero and different from local");
    }

    private void validateRequest(int remoteNodeId, RpcRequest request) {
        validateTarget(remoteNodeId);
        Objects.requireNonNull(request, "request");
        if (request.requestId() != 0) throw new IllegalArgumentException("Outbound requestId must be zero");
    }

    private void checkManagementThread() {
        if (inHandler.get() || Thread.currentThread() == timerThread)
            throw new IllegalStateException("Blocking lifecycle operation in RPC callback/timer");
        for (EventLoopGroup group : new EventLoopGroup[] {boss, workers, externalGroup}) {
            if (group != null) for (var executor : group)
                if (executor.inEventLoop()) throw new IllegalStateException("Blocking lifecycle operation on RPC EventLoop");
        }
    }

    /** Warning tagged with this Node; the message is built only by callers that already decided to log. */
    void warn(String message, Throwable cause) {
        if (LOG.isLoggable(System.Logger.Level.WARNING))
            LOG.log(System.Logger.Level.WARNING, "[rpc node " + Integer.toUnsignedString(nodeId) + "] " + message, cause);
    }
}
