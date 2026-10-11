package cn.managame.rpc.node;

import cn.managame.network.connection.*;
import cn.managame.network.connector.ConnectCallback;
import cn.managame.network.netty.*;
import cn.managame.rpc.call.*;
import cn.managame.rpc.error.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.timeout.IdleState;
import io.netty.util.*;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.managame.rpc.error.RpcErrorCodes.*;

/**
 * TCP RPC endpoint with peer-scoped calls and fixed connection slots.
 * start/close are blocking management operations, forbidden inside this node's handlers,
 * EventLoops and timer. Sending does not acquire the topology lifecycle lock.
 */
public final class RpcNode implements AutoCloseable {
    private enum State { NEW, RUNNING, CLOSED }
    private static final AttributeKey<RpcConnectionContext> CONTEXT =
            AttributeKey.valueOf(RpcNode.class, "context");
    private static final System.Logger LOG = System.getLogger(RpcNode.class.getName());
    private final int nodeId, maxFrameSize;
    private final SocketAddress bindAddress;
    private final RpcHandler handler;
    private final long callTimeout, handshakeTimeout, reconnectDelay, heartbeatInterval, heartbeatTimeout;
    private final long reconnectRandomDelay;
    private final Map<io.netty.channel.ChannelOption<?>, Object> channelOptions;
    private final List<java.util.function.Consumer<io.netty.channel.ChannelPipeline>> transports;
    private final byte[] secret;
    private final long clockSkewMillis;
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    // Accepted initiator nonces within the clock-skew window; rejects replayed handshakes.
    private final ConcurrentHashMap<java.nio.ByteBuffer, Long> seenNonces = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    final ConcurrentHashMap<Integer, RpcPeer> peers = new ConcurrentHashMap<>();
    final AtomicInteger requestIds = new AtomicInteger(1);
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    // A close barrier for admitted API work, including late pending registrations.
    private final Phaser operations = new Phaser(1);
    private final Set<Thread> notifications = ConcurrentHashMap.newKeySet();
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
        callTimeout = b.callTimeout; handshakeTimeout = b.handshakeTimeout; reconnectDelay = b.reconnectDelay;
        heartbeatInterval = b.heartbeatInterval; heartbeatTimeout = b.heartbeatTimeout;
        reconnectRandomDelay = b.reconnectRandomDelay < 0 ? reconnectDelay / 4 : b.reconnectRandomDelay;
        channelOptions = Map.copyOf(b.channelOptions); transports = List.copyOf(b.transports);
        secret = b.handshakeSecret == null ? null : b.handshakeSecret.clone(); clockSkewMillis = b.handshakeClockSkew;
    }
    public static RpcNodeBuilder builder() { return new RpcNodeBuilder(); }
    public int nodeId() { return nodeId; }
    /** Snapshot only: at least one bound connection is active; does not promise delivery. */
    public boolean isPeerConnected(int remoteNodeId) {
        RpcPeer peer = peers.get(remoteNodeId);
        return state == State.RUNNING && peer != null && Arrays.stream(peer.slots).anyMatch(slot -> {
            Connection connection = slot.connection.get();
            return connection != null && connection.isActive();
        });
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
                boss = new NioEventLoopGroup(1);
                workers = new NioEventLoopGroup();
                RpcConnectionHandler bridge = new RpcConnectionHandler(this);
                java.util.function.Consumer<io.netty.channel.ChannelPipeline> pipeline = p -> {
                    for (var transport : transports) transport.accept(p);   // byte-level handlers first
                    RpcWire.configurePipeline(p, maxFrameSize, heartbeatInterval, heartbeatTimeout);
                };
                var clientBuilder = NetworkClient.builder().eventLoopGroup(workers).handler(bridge).pipeline(pipeline);
                var serverBuilder = NetworkServer.builder().bindAddress(bindAddress).bossGroup(boss).workerGroup(workers)
                        .handler(bridge).pipeline(pipeline);
                channelOptions.forEach((option, value) -> { applyOption(clientBuilder, serverBuilder, option, value); });
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
    private static <T> void applyOption(cn.managame.network.netty.NetworkClientBuilder client,
                                        cn.managame.network.netty.NetworkServerBuilder server,
                                        io.netty.channel.ChannelOption<T> option, Object value) {
        client.option(option, (T) value); server.childOption(option, (T) value);
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
            peer = peers.compute(remoteNodeId, (id, current) -> {
                if (current == null) return new RpcPeer(id, slotCount, address);
                if (current.target == null)
                    throw new IllegalStateException("Peer " + Integer.toUnsignedString(id)
                            + " already dialed this node; only one side of a pair may call addPeer");
                if (current.slots.length != slotCount)
                    throw new IllegalStateException("Peer slotCount differs; remove it first");
                if (current.target != null && !current.target.equals(address))
                    throw new IllegalStateException("Peer address differs; remove it first");
                current.target = address;
                return current;
            });
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
        } finally { operations.arriveAndDeregister(); }
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
        try {
            peer = peers.get(remoteNodeId);
            if (peer == null) {
                ReferenceCountUtil.release(body); body = null;
                fireFail(remoteNodeId, request.command(), state == State.RUNNING ? PEER_NOT_FOUND : NODE_CLOSED, callback);
                return;
            }
            if (!peer.hasCandidate()) {
                ReferenceCountUtil.release(body); body = null;
                fireFail(remoteNodeId, request.command(), UNAVAILABLE, callback);
                return;
            }
            int id = nextRequestId();
            body = null; // RpcWire consumes it, including exceptions.
            frame = RpcWire.encodeRequest(request, id, maxFrameSize);
            pending = new PendingCall(id, request.command(), callback);
            if (peer.pending.putIfAbsent(id, pending) != null)
                throw new RpcException("requestId collided with an outstanding call");
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
            if (peer != null && pending != null && peer.pending.remove(pending.id, pending)) {
                pending.cancelTimeout();
                tryRemovePassive(peer);
            }
            throw error;
        } finally {
            ReferenceCountUtil.release(body);
            ReferenceCountUtil.release(frame);
            operations.arriveAndDeregister();
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
            if (!peer.hasCandidate()) return RpcSendStatus.UNAVAILABLE;
            body = null;
            return send(peer, -1, request.routeKey(), RpcWire.encodeRequest(request, 0, maxFrameSize));
        } finally {
            ReferenceCountUtil.release(body);
            operations.arriveAndDeregister();
        }
    }

    /** First tries the request's source slot; fallback uses routeKey, never requestId. Consumes the body. */
    public RpcSendStatus reply(int remoteNodeId, int sourceSlotId, long routeKey, RpcResponse response) {
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
            if (!peer.hasCandidate()) return RpcSendStatus.UNAVAILABLE;
            body = null;
            return send(peer, sourceSlotId, routeKey, RpcWire.encodeResponse(response, maxFrameSize));
        } finally {
            ReferenceCountUtil.release(body);
            operations.arriveAndDeregister();
        }
    }

    // This helper always consumes frame. Rejection retains it for the next slot; ACCEPTED ends traversal.
    RpcSendStatus send(RpcPeer peer, int preferred, long routeKey, ByteBuf frame) {
        boolean accepted = false;
        try {
            if (!current(peer)) return RpcSendStatus.UNAVAILABLE;
            if (preferred >= 0 && tryWrite(peer.slots[preferred], frame)) {
                accepted = true;
                return RpcSendStatus.ACCEPTED;
            }
            int start = peer.startSlot(routeKey);
            for (int i = 0; i < peer.slots.length; i++) {
                int index = (start + i) % peer.slots.length;
                if (index == preferred) continue;
                if (!current(peer)) break;
                if (tryWrite(peer.slots[index], frame)) {
                    accepted = true;
                    return RpcSendStatus.ACCEPTED;
                }
            }
            return RpcSendStatus.UNAVAILABLE;
        } finally { if (!accepted) frame.release(); }
    }
    private boolean tryWrite(ConnectionSlot slot, ByteBuf frame) {
        Connection connection = slot.connection.get();
        if (connection == null || !connection.isActive() || !connection.isWritable()) return false;
        try { return connection.write(frame) == WriteStatus.ACCEPTED; }
        catch (RuntimeException error) { connection.close(); throw error; }
    }

    private void connect(RpcPeer peer, ConnectionSlot slot) {
        if (!continueRecovery(peer, slot)) return;
        client.connectAsync(peer.target, new ConnectCallback() {
            public void onSuccess(Connection connection) {
                RpcConnectionContext context = connection.get(CONTEXT);
                boolean associated;
                synchronized (lifecycleLock) {
                    associated = current(peer) && peer.target != null && context != null
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
                byte[] nonce = newNonce();
                context.sentNonce = nonce;
                writeControl(connection, RpcWire.encodeHandshake(new RpcHandshake(nodeId, slot.id, peer.slots.length),
                        new RpcWire.HandshakeProof(System.currentTimeMillis(), nonce, ZERO_NONCE), secret));
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
        if (peer.target != null && slot.connection.get() == null) return true;
        slot.connecting.set(false);
        return current(peer) && peer.target != null && slot.connection.get() == null
                && slot.connecting.compareAndSet(false, true);
    }

    long nextReconnectDelayMillis() {
        return reconnectDelay + (reconnectRandomDelay == 0 ? 0 : ThreadLocalRandom.current().nextLong(reconnectRandomDelay + 1));
    }

    void connected(Connection connection) {
        synchronized (lifecycleLock) {
            if (state != State.RUNNING) { connection.close(); return; }
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
                handshake(connection, context, RpcWire.decodeHandshake(frame, secret));
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
            log("Invalid RPC frame", error);
            connection.close();
        }
    }

    private void handshake(Connection connection, RpcConnectionContext context, RpcWire.AuthenticatedHandshake received) {
        RpcHandshake hello = received.identity();
        RpcWire.HandshakeProof proof = received.proof();
        if (hello.nodeId() == nodeId || context.slot != null || !context.handshakeFinished.compareAndSet(false, true))
            throw new CorruptedFrameException("Duplicate, expired or self handshake");
        if (context.expectedPeer != null) {
            // Our own outbound connection: the reply must echo the nonce we sent.
            byte[] sent = context.sentNonce;
            if (sent == null || !Arrays.equals(sent, proof.peerNonce()))
                throw new CorruptedFrameException("Handshake reply does not answer this connection");
        } else {
            if (!Arrays.equals(ZERO_NONCE, proof.peerNonce())) throw new CorruptedFrameException("Unexpected handshake reply");
            if (secret != null) checkFresh(proof);
        }
        context.timeout.cancel();
        synchronized (lifecycleLock) {
            if (state != State.RUNNING || !connection.isActive()) { connection.close(); return; }
            if (context.expectedPeer != null) {
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
            } else {
                peers.compute(hello.nodeId(), (id, old) -> {
                    if (old != null && old.target != null)
                        throw new CorruptedFrameException("Both nodes called addPeer for each other (remote "
                                + Integer.toUnsignedString(id) + "); only one side of a pair may dial");
                    RpcPeer peer = old == null ? new RpcPeer(id, hello.slotCount(), null) : old;
                    if (peer.slots.length != hello.slotCount())
                        throw new CorruptedFrameException("Peer slotCount mismatch");
                    ConnectionSlot slot = peer.slots[hello.slotId()];
                    if (slot.connection.get() != null) throw new CorruptedFrameException("Duplicate slot");
                    // A READY connection is not published until its reply handshake is admitted.
                    if (!writeControl(connection, RpcWire.encodeHandshake(
                            new RpcHandshake(nodeId, slot.id, peer.slots.length),
                            new RpcWire.HandshakeProof(System.currentTimeMillis(), newNonce(), proof.nonce()), secret))) return old;
                    if (!connection.isActive() || !slot.connection.compareAndSet(null, connection)) {
                        connection.close();
                        return old;
                    }
                    context.peer = peer;
                    context.slot = slot;
                    // An existing outbound recovery chain stops itself on observing this binding.
                    return peer;
                });
            }
        }
    }

    private static final byte[] ZERO_NONCE = new byte[16];
    private byte[] newNonce() {
        if (secret == null) return ZERO_NONCE.clone();
        byte[] nonce = new byte[16]; random.nextBytes(nonce); return nonce;
    }
    /** Initiating handshake with a secret: timestamp within the skew window and nonce never seen in it. */
    private void checkFresh(RpcWire.HandshakeProof proof) {
        long now = System.currentTimeMillis();
        if (Math.abs(now - proof.timestampMillis()) > clockSkewMillis) throw new CorruptedFrameException("Stale RPC handshake");
        long expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2 * clockSkewMillis);
        if (seenNonces.putIfAbsent(java.nio.ByteBuffer.wrap(proof.nonce()), expires) != null)
            throw new CorruptedFrameException("Replayed RPC handshake");
        if (seenNonces.size() > 1024) {
            long nanos = System.nanoTime();
            seenNonces.values().removeIf(expiry -> expiry - nanos < 0);
        }
    }

    private void response(RpcPeer peer, ByteBuf frame) {
        if (frame.readableBytes() < 4) throw new CorruptedFrameException("Response lacks requestId");
        int id = frame.readInt();
        if (id == 0) throw new CorruptedFrameException("Zero response requestId");
        PendingCall pending = peer.pending.remove(id);
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
                if (current(peer) && peer.target != null
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

    private void fireRequest(RpcPeer peer, int slotId, RpcRequest request) {
        boolean previous = inHandler.get();
        inHandler.set(true);
        try { handler.onRequest(peer.nodeId, slotId, request); }
        catch (RuntimeException error) {
            log("RpcHandler.onRequest failed", error);
            if (request.requestId() != 0 && current(peer)) {
                RpcResponse response = new RpcResponse(request.requestId(), HANDLER_ERROR, null, null);
                try {
                    RpcSendStatus status = send(peer, slotId, request.routeKey(),
                            RpcWire.encodeResponse(response, maxFrameSize));
                    if (status != RpcSendStatus.ACCEPTED) log("Handler error response was not admitted", null);
                } catch (RuntimeException sendError) { log("Handler error response failed", sendError); }
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
        catch (RuntimeException error) { log("RPC handler failed", error); }
        finally { inHandler.set(previous); }
    }
    private void fail(RpcPeer peer, PendingCall pending, int error) {
        if (!peer.pending.remove(pending.id, pending)) return;
        pending.cancelTimeout();
        try { fireFail(peer.nodeId, pending.command, error, pending.callback); }
        finally { tryRemovePassive(peer); }
    }
    private int nextRequestId() {
        int id;
        do { id = requestIds.getAndIncrement(); } while (id == 0);
        return id;
    }
    private void timeout(RpcPeer peer, PendingCall pending) {
        if (!peer.pending.remove(pending.id, pending)) return;
        pending.cancelTimeout();
        tryRemovePassive(peer);
        Thread notification = Thread.ofVirtual().name("rpc-" + Integer.toUnsignedString(nodeId) + "-timeout").unstarted(() -> {
            try { fireFail(peer.nodeId, pending.command, TIMEOUT, pending.callback); }
            finally { notifications.remove(Thread.currentThread()); }
        });
        notifications.add(notification);
        try {
            notification.start();
        } catch (RuntimeException | Error failure) {
            notifications.remove(notification);
            throw failure;
        }
    }
    private void tryRemovePassive(RpcPeer peer) {
        if (peer.target != null) return;
        peers.computeIfPresent(peer.nodeId, (id, current) -> {
            if (current != peer || peer.target != null || !peer.pending.isEmpty()) return current;
            for (ConnectionSlot slot : peer.slots) if (slot.connection.get() != null) return current;
            return null;
        });
    }
    private void detach(RpcPeer peer, int error) {
        peer.target = null;
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

    /**
     * Idempotent synchronous barrier. All admitted sends, callbacks on owned workers, connections
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
                closing = new ArrayList<>(peers.values());
                peers.clear();
            }
        }
        if (closing == null) { awaitClosed(); return; }
        try {
            operations.arriveAndAwaitAdvance();
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
        cleanup(() -> { if (boss != null) boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly(); });
        cleanup(() -> { if (workers != null) workers.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly(); });
        cleanup(() -> { if (timer != null) timer.stop(); });
        awaitNotifications();
        connections.clear();
    }
    private void awaitNotifications() {
        boolean interrupted = false;
        for (Thread notification : notifications) {
            while (true) {
                try { notification.join(); break; }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void cleanup(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { log("RPC shutdown resource failed", error); }
    }
    private void awaitClosed() {
        boolean interrupted = false;
        while (true) {
            try { closed.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private void enterOperation() {
        ensureRunning();
        operations.register();
        if (state != State.RUNNING) {
            operations.arriveAndDeregister();
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
        for (EventLoopGroup group : new EventLoopGroup[] { boss, workers }) {
            if (group != null) for (var executor : group)
                if (executor.inEventLoop()) throw new IllegalStateException("Blocking lifecycle operation on RPC EventLoop");
        }
    }
    static void log(String message, Throwable cause) {
        LOG.log(System.Logger.Level.WARNING, message, cause);
    }
}


