package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.RpcChecks;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcHandshake;
import cn.managame.rpc.protocol.RpcHeartbeat;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.transport.RpcTransport;

import cn.managame.network.*;

import io.netty.buffer.ByteBuf;
import io.netty.util.Timeout;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Internal owner of peer topology, TCP handshakes, reconnects and connection selection. */
final class RpcConnections {
    private final Object lock = new Object();
    private final int nodeId, maxPendingCalls;
    private final io.netty.util.Timer timer;
    private final RpcTransport transport;
    private final BooleanSupplier running, closed;
    private final RpcObserver observer;

    // A verified physical connection keeps its identity even after disconnection. No call state.
    private final AttributeKey<ConnectionHandler> identityKey =
            AttributeKey.of("rpc-identity", ConnectionHandler.class);
    private final int connectionsPerPeer, maxConnectionsPerPeer, maxPeers, maxPendingHandshakes;
    private int pendingHandshakes;
    private final Duration reconnectDelay;
    private final boolean closeOnReadIdle;
    private final long maxReconnectNanos;
    private final long handshakeTimeoutNanos, heartbeatIntervalNanos, heartbeatTimeoutNanos;
    private final ConnectionSelector selector;
    private final IntPredicate peerAdmission;
    private final ConcurrentHashMap<Integer, RpcPeer> peers = new ConcurrentHashMap<>();
    // Lifecycle tracking only; receive/send use connection attributes, never this map.
    private final Map<String, ConnectionHandler> handlers = new HashMap<>();
    // Only actively dialed peers have a connection plan. Never consulted by ordinary message IO.
    private final Map<RpcPeer, Outbound> outbound = new IdentityHashMap<>();

    /**
     * Cold connection state, accessed only under the topology lock; no Node or Peer back-reference.
     */
    static final class Outbound {
        final InetSocketAddress address;
        // Each slot holds either a scheduled Timeout or the current attempt identity, never both.
        final Object[] tasks;
        final long[] retryCeilings;
        RpcConnectionConflictException failure;
        List<ConnectCallback> callbacks;

        Outbound(String host, int port, int count) {
            address = InetSocketAddress.createUnresolved(host, port);
            tasks = new Object[count];
            retryCeilings = new long[count];
        }

        void configure(String host, int port, int count, ConnectCallback callback) {
            if (failure != null) throw failure;
            if (!address.getHostString().equals(host)
                    || address.getPort() != port
                    || tasks.length != count)
                throw new IllegalArgumentException("Conflicting Peer configuration");
            if (callback == null) return;
            if (callbacks == null) callbacks = new ArrayList<>();
            if (callbacks.size() >= 64) throw RpcException.OVERLOADED;
            callbacks.add(callback);
        }

        Object begin(int slot, Timeout expected) {
            if (failure != null || tasks[slot] != expected) return null;
            return tasks[slot] = new Object();
        }

        boolean matches(int slot, Object expected) {
            return expected != null && tasks[slot] == expected;
        }

        boolean finish(int slot, Object expected) {
            if (!matches(slot, expected)) return false;
            tasks[slot] = null;
            return true;
        }

        // Called only when installing a retry; no per-message state or allocation.
        long retryDelay(int slot, long initial, long maximum) {
            long previous = retryCeilings[slot];
            long ceiling =
                    previous == 0
                            ? initial
                            : previous >= maximum - previous ? maximum : previous * 2;
            retryCeilings[slot] = ceiling;
            // Equal jitter: [ceiling / 2, ceiling], positive even for a 1 ns configuration.
            long minimum = Math.max(1, ceiling / 2);
            return minimum + ThreadLocalRandom.current().nextLong(ceiling - minimum + 1);
        }

        void connected(int slot) {
            cancel(slot);
            retryCeilings[slot] = 0;
        }

        void cancel(int slot) {
            if (tasks[slot] instanceof Timeout timeout) timeout.cancel();
            tasks[slot] = null;
        }

        void cancelAll() {
            for (int i = 0; i < tasks.length; i++) cancel(i);
        }
    }

    RpcConnections(
            int nodeId,
            int maxPendingCalls,
            RpcNode.Builder b,
            io.netty.util.Timer timer,
            RpcTransport transport,
            BooleanSupplier running,
            BooleanSupplier closed,
            RpcObserver observer) {
        this.nodeId = nodeId;
        this.maxPendingCalls = maxPendingCalls;
        this.timer = timer;
        this.transport = transport;
        this.running = running;
        this.closed = closed;
        this.observer = observer;
        connectionsPerPeer = b.connections;
        maxPeers = b.maxPeers;
        maxPendingHandshakes = b.maxPendingHandshakes;
        maxConnectionsPerPeer = b.maxConnections;
        reconnectDelay = b.reconnect;
        closeOnReadIdle = !b.readIdle.isZero();
        maxReconnectNanos =
                b.maxReconnect == null
                        ? Math.max(reconnectDelay.toNanos(), TimeUnit.SECONDS.toNanos(30))
                        : b.maxReconnect.toNanos();
        handshakeTimeoutNanos = RpcChecks.timeoutNanos(b.handshake);
        heartbeatIntervalNanos = RpcChecks.timeoutNanos(b.heartbeatInterval);
        heartbeatTimeoutNanos = RpcChecks.timeoutNanos(b.heartbeatTimeout);
        selector = b.selector;
        peerAdmission = b.admission;
    }

    private boolean matchesAttempt(RpcPeer peer, int slot, Object attempt) {
        var plan = outbound.get(peer);
        return plan != null && plan.matches(slot, attempt);
    }

    private boolean hasAttempt(RpcPeer peer, int slot) {
        var plan = outbound.get(peer);
        return plan != null && plan.tasks[slot] != null && !(plan.tasks[slot] instanceof Timeout);
    }

    private void observe(String event, Throwable failure) {
        observer.observe(event, null, null, null, failure);
    }

    private void observe(
            String event,
            Integer peer,
            Integer requestId,
            Connection connection,
            Throwable failure) {
        observer.observe(event, peer, requestId, connection, failure);
    }

    Collection<RpcPeer> peersSnapshot() { return List.copyOf(peers.values()); }

    RpcPeer peer(int id) {
        return peers.get(id);
    }

    RpcPeer peer(Connection connection) {
        var identity = Objects.requireNonNull(connection).get(identityKey);
        return identity != null && identity.ready && peers.get(identity.nodeId()) == identity.peer
                ? identity.peer
                : null;
    }

    private ConnectionHandler activeHandler(Connection connection) {
        if (connection == null) return null;
        var handler = connection.get(identityKey);
        return handler != null && handler.connection == connection && handler.registered
                ? handler
                : null;
    }

    NetworkHandler newHandler(RpcMessages messages, boolean incoming) {
        return new ConnectionHandler(messages, !incoming);
    }

    /** One handler owns both connection identity and network events. */
    private final class ConnectionHandler implements NetworkHandler {
        private final RpcMessages messages;
        final boolean outgoing;
        Connection connection;
        RpcPeer peer;
        int slot;
        volatile boolean ready, registered;
        Object attempt;
        long deadline;
        boolean rejected, processing;
        // One scheduled control task per physical connection: handshake, next probe or PONG
        // deadline.
        Timeout timeout;
        int heartbeatSequence;
        boolean awaitingHeartbeat;

        ConnectionHandler(RpcMessages messages, boolean outgoing) {
            this.messages = messages;
            this.outgoing = outgoing;
        }

        int nodeId() {
            return peer == null ? 0 : peer.nodeId();
        }

        void clearControl() {
            if (timeout != null) timeout.cancel();
            timeout = null;
            attempt = null;
            deadline = 0;
            rejected = false;
            processing = false;
            awaitingHeartbeat = false;
        }

        public void onConnected(Connection connection) {
            RuntimeException rejected = null;
            synchronized (lock) {
                if (this.connection != null || connection.get(identityKey) != null)
                    throw new IllegalStateException("RPC handler must belong to one connection");
                this.connection = connection;
                connection.set(identityKey, this);
                if (!outgoing) {
                    try {
                        if (!running.getAsBoolean()) throw RpcException.UNAVAILABLE;
                        deadline = System.nanoTime() + handshakeTimeoutNanos;
                        begin(this);
                    } catch (RuntimeException failure) {
                        rejected = failure;
                    }
                }
            }
            if (rejected != null) {
                disconnect(this);
                if (rejected instanceof RpcException rpc && rpc.error() == RpcError.OVERLOADED)
                    observe("handshake-overloaded", rejected);
            }
        }

        public void onMessage(Connection connection, Object input) {
            if (connection != this.connection
                    || !registered
                    || !(input instanceof ByteBuf frame)
                    || !messages.accepts(frame)) {
                onDisconnected(connection);
                connection.close();
                return;
            }
            RpcMessage message;
            try {
                message = messages.decode(frame);
                if (!ready || message instanceof RpcHandshake) {
                    handshake(this, message);
                    return;
                }
                if (!canReceive(this)) return;
                if (message instanceof RpcHeartbeat heartbeat) {
                    heartbeat(this, heartbeat);
                    return;
                }
            } catch (Exception failure) {
                observe("frame-decode-failed", nodeId(), null, connection, failure);
                if (!ready) {
                    onDisconnected(connection);
                    connection.close();
                } else if (canReceive(this)) messages.protocolFailure(connection, peer, failure);
                return;
            }
            messages.receive(connection, peer, message);
        }

        public void onDisconnected(Connection connection) {
            if (connection != this.connection) return;
            boolean removed;
            synchronized (lock) {
                removed = drop(this);
            }
            if (removed) reconnect(peer, slot, reconnectDelay);
        }

        public void onIdle(Connection connection, IdleType type) {
            if (!closeOnReadIdle
                    || outgoing
                    || connection != this.connection
                    || !registered
                    || (type != IdleType.READ && type != IdleType.ALL)) return;
            try {
                disconnect(this);
            } finally {
                observe("read-idle", nodeId(), null, connection, null);
            }
        }

        public void onException(Connection connection, Throwable failure) {
            observe("network-error", null, null, connection, failure);
            connection.close();
        }
    }

    void connect(int id, String host, int port, ConnectCallback callback) {
        connect(id, host, port, connectionsPerPeer, callback);
    }

    /** A callback observes all slots ready for this connect invocation, once. */
    void connect(int id, String host, int port, int count, ConnectCallback callback) {
        Objects.requireNonNull(host, "host");
        if (count < 1 || count > maxConnectionsPerPeer)
            throw new IllegalArgumentException("connectionCount");
        if (host.isBlank()
                || host.chars().anyMatch(Character::isWhitespace)
                || host.contains("/")
                || host.contains("[")
                || host.contains("]")
                || host.equals("0.0.0.0")
                || host.equals("::")
                || port < 1
                || port > 65535)
            throw new IllegalArgumentException("Remote RPC listen address required");
        RpcPeer peer;
        boolean firstConnect;
        Runnable notification;
        synchronized (lock) {
            ensureOpen();
            if (!running.getAsBoolean())
                throw new IllegalStateException("Start RpcNode before connect");
            peer = peers.get(id);
            firstConnect = peer == null;
            if (peer == null) {
                peer = createPeer(id, count);
                outbound.put(peer, new Outbound(host, port, count));
            }
            var plan = outbound.get(peer);
            if (plan == null) throw new RpcConnectionConflictException(nodeId, id);
            plan.configure(host, port, count, callback);
            notification =
                    callback != null && allReady(peer)
                            ? connectNotification(peer, peer.connection(0), null)
                            : null;
        }
        for (int i = 0; i < count; i++)
            reconnect(peer, i, firstConnect ? Duration.ZERO : reconnectDelay);
        if (notification != null) notification.run();
    }

    /** Ends this Peer lifecycle; a later admitted inbound handshake may create a new Peer. */
    RuntimeException removePeer(int id, Consumer<RpcPeer> retiring, List<Runnable> notifications) {
        var closing = new ArrayList<ConnectionHandler>();
        synchronized (lock) {
            var peer = peers.remove(id);
            if (peer != null) {
                peer.retire();
                retiring.accept(peer);
                var notification =
                        connectNotification(peer, null, new IllegalStateException("Peer removed"));
                if (notification != null) notifications.add(notification);
                var plan = outbound.remove(peer);
                if (plan != null) plan.cancelAll();
                for (var handler : handlers.values())
                    if (handler.peer == peer) closing.add(handler);
                for (var handler : closing) drop(handler);
            }
        }
        return closeConnections(closing, notifications);
    }

    private void ensureOpen() {
        if (closed.getAsBoolean()) throw new IllegalStateException("RpcNode closed");
    }

    private void reconnect(RpcPeer peer, int slot, Duration delay) {
        if (peer == null) return;
        try {
            synchronized (lock) {
                var plan = outbound.get(peer);
                if (!running.getAsBoolean()
                        || peer.isClosed()
                        || peers.get(peer.nodeId()) != peer
                        || plan == null
                        || plan.failure != null
                        || plan.tasks[slot] != null
                        || isReadyConnection(peer, peer.connection(slot))) return;
                long waitNanos =
                        delay.isZero()
                                ? 0
                                : plan.retryDelay(
                                        slot, reconnectDelay.toNanos(), maxReconnectNanos);
                plan.tasks[slot] =
                        timer.newTimeout(
                                timeout ->
                                        Thread.startVirtualThread(
                                                () -> connect(peer, slot, timeout)),
                                waitNanos,
                                TimeUnit.NANOSECONDS);
            }
        } catch (RejectedExecutionException | IllegalStateException e) {
            observe("reconnect-rejected", peer.nodeId(), null, null, e);
        }
    }

    /** Timer threads only dispatch; connection work may wait for topology or a provider. */
    private void connect(RpcPeer peer, int slot, Timeout expected) {
        Object attempt;
        InetSocketAddress address;
        synchronized (lock) {
            var plan = outbound.get(peer);
            if (plan == null) return;
            attempt = plan.begin(slot, expected);
            if (attempt == null) return;
            if (!running.getAsBoolean()
                    || peers.get(peer.nodeId()) != peer
                    || isReadyConnection(peer, peer.connection(slot))) {
                plan.finish(slot, attempt);
                return;
            }
            address = plan.address;
        }
        try {
            transport.connect(
                    address,
                    new ConnectCallback() {
                        public void onSuccess(Connection connection) {
                            outgoing(peer, slot, attempt, connection);
                        }

                        public void onFailure(Throwable failure) {
                            connectFailed(peer, slot, attempt, failure);
                        }
                    });
        } catch (RuntimeException e) {
            connectFailed(peer, slot, attempt, e);
        }
    }

    private void connectFailed(RpcPeer peer, int slot, Object attempt, Throwable failure) {
        synchronized (lock) {
            var plan = outbound.get(peer);
            if (plan == null || !plan.finish(slot, attempt)) return;
        }
        reconnect(peer, slot, reconnectDelay);
        observe("connect-failed", peer.nodeId(), null, null, failure);
    }

    private void outgoing(RpcPeer peer, int slot, Object attempt, Connection connection) {
        var b = connection.get(identityKey);
        try {
            synchronized (lock) {
                if (b == null || b.connection != connection || !b.outgoing || b.registered)
                    throw new IllegalStateException("Missing outgoing RPC handler");
                b.peer = peer;
                b.slot = slot;
                b.attempt = attempt;
                b.deadline = System.nanoTime() + handshakeTimeoutNanos;
                if (!running.getAsBoolean()
                        || peers.get(peer.nodeId()) != peer
                        || peer.isClosed()
                        || !matchesAttempt(peer, slot, attempt)
                        || !connection.isActive()) throw RpcException.UNAVAILABLE;
                begin(b);
            }
            if (!sendHandshake(
                    b, RpcHandshake.Kind.HELLO, peer.nodeId(), slot, peer.connectionCount()))
                disconnect(b);
        } catch (RuntimeException failure) {
            if (b != null && b.connection == connection) disconnect(b);
            else connection.close();
            if (failure instanceof RpcException rpc && rpc.error() == RpcError.OVERLOADED)
                observe("handshake-overloaded", failure);
            connectFailed(peer, slot, attempt, failure);
        }
    }

    /**
     * Only reserves state and installs the deadline. The caller closes failures outside the lock.
     */
    private void begin(ConnectionHandler b) {
        if (pendingHandshakes >= maxPendingHandshakes) throw RpcException.OVERLOADED;
        if (b.connection.type() != ConnectionType.TCP
                || handlers.putIfAbsent(b.connection.id(), b) != null)
            throw new RpcProtocolException("Duplicate or non-TCP connection");
        b.registered = true;
        pendingHandshakes++;
        b.timeout =
                timer.newTimeout(
                        ignored -> Thread.startVirtualThread(() -> handshakeExpired(b)),
                        Math.max(0, b.deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
    }

    private void handshakeExpired(ConnectionHandler b) {
        synchronized (lock) {
            if (b.ready || activeHandler(b.connection) != b) return;
            drop(b);
        }
        closeAndReconnect(b, true);
        observe("handshake-timeout", b.peer == null ? null : b.nodeId(), null, b.connection, null);
    }

    /**
     * Rechecked after admission and encoding; a removed/replaced reservation cannot be published.
     */
    private void requireHandshake(ConnectionHandler b) {
        if (activeHandler(b.connection) != b
                || b.ready
                || !running.getAsBoolean()
                || !b.connection.isActive()) throw RpcException.UNAVAILABLE;
        if (System.nanoTime() - b.deadline >= 0) throw RpcException.signal(RpcError.TIMEOUT);
        if (b.peer != null
                && (b.peer.isClosed()
                        || peers.get(b.nodeId()) != b.peer
                        || (b.outgoing && !matchesAttempt(b.peer, b.slot, b.attempt))))
            throw RpcException.UNAVAILABLE;
    }

    private boolean sendHandshake(
            ConnectionHandler b, RpcHandshake.Kind kind, int target, int slot, int count) {
        // Codec and native submission are outside the topology lock. Validate again after encoding.
        return b.messages.sendOnConnection(
                        b.connection,
                        new RpcHandshake(kind, nodeId, target, slot, count),
                        () -> {
                            synchronized (lock) {
                                requireHandshake(b);
                            }
                        })
                == RpcTransport.Submission.ACCEPTED;
    }

    private void handshake(ConnectionHandler b, RpcMessage header) {
        try {
            synchronized (lock) {
                requireHandshake(b);
                if (b.rejected || b.processing)
                    throw new RpcProtocolException("Repeated handshake");
                b.processing = true;
            }
            if (!(header instanceof RpcHandshake h))
                throw new RpcProtocolException("Expected handshake");
            if (h.targetNodeId() != nodeId || h.connectionCount() > maxConnectionsPerPeer)
                throw new RpcProtocolException("Handshake admission rejected");
            RpcPeer conflict = null;
            synchronized (lock) {
                requireHandshake(b);
                if (b.outgoing) {
                    if ((h.kind() != RpcHandshake.Kind.ACK
                                    && h.kind() != RpcHandshake.Kind.REJECT_DIRECTION)
                            || h.sourceNodeId() != b.nodeId()
                            || h.slotIndex() != b.slot
                            || h.connectionCount() != b.peer.connectionCount())
                        throw new RpcProtocolException("ACK mismatch");
                    if (h.kind() == RpcHandshake.Kind.REJECT_DIRECTION) conflict = b.peer;
                } else {
                    if (h.kind() != RpcHandshake.Kind.HELLO)
                        throw new RpcProtocolException("Expected HELLO");
                    var peer = peers.get(h.sourceNodeId());
                    if (h.sourceNodeId() == nodeId
                            && (peer == null
                                    || !outbound.containsKey(peer)
                                    || h.slotIndex() >= peer.connectionCount()
                                    || !hasAttempt(peer, h.slotIndex())))
                        throw new RpcProtocolException("Unexpected loopback connection");
                    if (peer != null && outbound.containsKey(peer) && h.sourceNodeId() != nodeId) {
                        b.rejected = true;
                        conflict = peer;
                    } else {
                        if (peer == null) peer = createPeer(h.sourceNodeId(), h.connectionCount());
                        if (peer.isClosed() || peer.connectionCount() != h.connectionCount())
                            throw new RpcProtocolException("Peer connection count mismatch");
                        b.peer = peer;
                        b.slot = h.slotIndex();
                    }
                }
                if (conflict == null) admit(b);
            }
            // Reserve before user admission, so removal also invalidates a blocked candidate.
            if (!peerAdmission.test(h.sourceNodeId()))
                throw new RpcProtocolException("Handshake admission rejected");
            synchronized (lock) {
                requireHandshake(b);
                if (conflict != null
                        && (conflict.isClosed() || peers.get(h.sourceNodeId()) != conflict))
                    throw RpcException.UNAVAILABLE;
            }
            if (conflict != null) {
                try {
                    // Keep the rejected inbound socket until its initiator closes or it times out.
                    if (!b.outgoing
                            && !sendHandshake(
                                    b,
                                    RpcHandshake.Kind.REJECT_DIRECTION,
                                    h.sourceNodeId(),
                                    h.slotIndex(),
                                    h.connectionCount())) disconnect(b);
                } finally {
                    failConnectionDirection(
                            conflict,
                            b.connection,
                            new RpcConnectionConflictException(nodeId, h.sourceNodeId()));
                }
                return;
            }
            if (!b.outgoing
                    && !sendHandshake(
                            b,
                            RpcHandshake.Kind.ACK,
                            b.nodeId(),
                            b.slot,
                            b.peer.connectionCount())) {
                disconnect(b);
                return;
            }
            Runnable notification;
            synchronized (lock) {
                requireHandshake(b);
                b.ready = true;
                pendingHandshakes--;
                b.clearControl();
                b.peer.connectionReady(b.slot, b.connection, b.outgoing || b.nodeId() != nodeId);
                if (b.outgoing) outbound.get(b.peer).connected(b.slot);
                if (b.outgoing) scheduleHeartbeat(b, heartbeatIntervalNanos);
                notification =
                        allReady(b.peer) ? connectNotification(b.peer, b.connection, null) : null;
            }
            observe("connection-ready", b.nodeId(), null, b.connection, null);
            if (notification != null) notification.run();
        } catch (RuntimeException failure) {
            boolean registered;
            synchronized (lock) {
                registered = b.registered;
                drop(b);
            }
            closeAndReconnect(b, registered);
            if (registered)
                observe(
                        failure instanceof RpcException rpc && rpc.error() == RpcError.TIMEOUT
                                ? "handshake-timeout"
                                : "handshake-rejected",
                        b.peer == null ? null : b.nodeId(),
                        null,
                        b.connection,
                        failure);
        }
    }

    // Called under the topology lock. No codec, transport or user callback runs on the timer wheel.
    private void scheduleHeartbeat(ConnectionHandler b, long delay) {
        if (b.timeout != null) b.timeout.cancel();
        b.timeout =
                timer.newTimeout(
                        task -> Thread.startVirtualThread(() -> heartbeatTick(b, task)),
                        delay,
                        TimeUnit.NANOSECONDS);
    }

    private void heartbeatTick(ConnectionHandler b, Timeout task) {
        try {
            boolean expired;
            int sequence;
            synchronized (lock) {
                if (!canReceive(b) || b.timeout != task) return;
                expired = b.awaitingHeartbeat;
                if (expired) {
                    // A timer is only a wakeup; the monotonic deadline remains authoritative.
                    long remaining = b.deadline - System.nanoTime();
                    if (remaining > 0) {
                        scheduleHeartbeat(b, remaining);
                        return;
                    }
                    drop(b);
                } else {
                    b.heartbeatSequence =
                            b.heartbeatSequence == Integer.MAX_VALUE ? 1 : b.heartbeatSequence + 1;
                    b.awaitingHeartbeat = true;
                    b.deadline = System.nanoTime() + heartbeatTimeoutNanos;
                    // Install before send: an in-process transport may deliver PONG synchronously.
                    scheduleHeartbeat(b, heartbeatTimeoutNanos);
                }
                sequence = b.heartbeatSequence;
            }
            if (expired) {
                closeAndReconnect(b, true);
                observe("heartbeat-timeout", b.nodeId(), null, b.connection, null);
            } else {
                sendHeartbeat(b, new RpcHeartbeat(RpcHeartbeat.Kind.PING, sequence));
            }
        } catch (RuntimeException failure) {
            heartbeatFailed(b, failure);
        }
    }

    private void heartbeat(ConnectionHandler b, RpcHeartbeat message) {
        try {
            if (message.kind() == RpcHeartbeat.Kind.PING) {
                if (b.outgoing)
                    throw new RpcProtocolException("Only the connection initiator sends PING");
                sendHeartbeat(b, new RpcHeartbeat(RpcHeartbeat.Kind.PONG, message.sequence()));
                return;
            }
            boolean expired;
            synchronized (lock) {
                if (!canReceive(b)) return;
                if (!b.outgoing)
                    throw new RpcProtocolException("Only the connection initiator receives PONG");
                if (!b.awaitingHeartbeat || message.sequence() != b.heartbeatSequence) return;
                expired = System.nanoTime() - b.deadline >= 0;
                if (expired) drop(b);
                else {
                    b.awaitingHeartbeat = false;
                    scheduleHeartbeat(b, heartbeatIntervalNanos);
                }
            }
            if (expired) {
                closeAndReconnect(b, true);
                observe("heartbeat-timeout", b.nodeId(), null, b.connection, null);
            }
        } catch (RuntimeException failure) {
            heartbeatFailed(b, failure);
        }
    }

    private void sendHeartbeat(ConnectionHandler b, RpcHeartbeat message) {
        var result =
                b.messages.sendOnConnection(
                        b.connection,
                        message,
                        () -> {
                            synchronized (lock) {
                                if (!canReceive(b)) throw RpcException.UNAVAILABLE;
                                if (message.kind() == RpcHeartbeat.Kind.PING
                                        && (!b.awaitingHeartbeat
                                                || message.sequence() != b.heartbeatSequence
                                                || System.nanoTime() - b.deadline >= 0))
                                    throw RpcException.UNAVAILABLE;
                            }
                        });
        // A full write queue is given the remaining PONG deadline, never another Slot.
        if (result == RpcTransport.Submission.UNAVAILABLE) disconnect(b);
    }

    private void heartbeatFailed(ConnectionHandler b, RuntimeException failure) {
        try {
            disconnect(b);
        } finally {
            observe("heartbeat-failed", b.nodeId(), null, b.connection, failure);
        }
    }

    private void admit(ConnectionHandler candidate) {
        for (var existing : handlers.values()) {
            if (existing == candidate
                    || existing.peer != candidate.peer
                    || existing.slot != candidate.slot
                    || !existing.connection.isActive()) continue;
            // One accepted end and one outgoing end belong to the same local TCP slot.
            if (candidate.nodeId() == nodeId && existing.outgoing != candidate.outgoing) continue;
            throw new RpcProtocolException("Duplicate connection for Peer slot " + candidate.slot);
        }
    }

    private void failConnectionDirection(
            RpcPeer peer, Connection connection, RpcConnectionConflictException failure) {
        var closing = new ArrayList<ConnectionHandler>();
        Runnable notification;
        synchronized (lock) {
            var plan = outbound.get(peer);
            if (plan == null || plan.failure != null) return;
            plan.failure = failure;
            plan.cancelAll();
            for (var handler : handlers.values()) if (handler.peer == peer) closing.add(handler);
            for (var handler : closing) drop(handler);
            notification = connectNotification(peer, null, failure);
        }
        closeConnections(closing, null);
        observe("connection-direction-conflict", peer.nodeId(), null, connection, failure);
        if (notification != null) notification.run();
    }

    // Called only under the topology lock. Discovery must remove departed nodes explicitly.
    private RpcPeer createPeer(int id, int count) {
        if (peers.size() >= maxPeers) throw RpcException.OVERLOADED;
        var peer = new RpcPeer(id, count, maxPendingCalls);
        peers.put(id, peer);
        return peer;
    }

    private void disconnect(ConnectionHandler b) {
        boolean removed;
        synchronized (lock) {
            removed = drop(b);
        }
        closeAndReconnect(b, removed);
    }

    private void closeAndReconnect(ConnectionHandler b, boolean removed) {
        try {
            b.connection.close();
        } finally {
            if (removed) reconnect(b.peer, b.slot, reconnectDelay);
        }
    }

    /**
     * State transition only; callers close and schedule retries after releasing the topology lock.
     */
    private boolean drop(ConnectionHandler b) {
        if (!b.registered || !handlers.remove(b.connection.id(), b)) return false;
        b.registered = false;
        if (!b.ready) pendingHandshakes--;
        if (b.peer != null) {
            b.peer.disconnected(b.slot, b.connection);
            var plan = outbound.get(b.peer);
            if (plan != null) plan.finish(b.slot, b.attempt);
            discardUnestablishedPeer(b.peer);
        }
        b.clearControl();
        return true;
    }

    /** Roll back inbound-only reservations after their last handshake candidate disappears. */
    private void discardUnestablishedPeer(RpcPeer peer) {
        if (outbound.containsKey(peer) || peer.isEstablished() || peers.get(peer.nodeId()) != peer)
            return;
        for (var candidate : handlers.values()) if (candidate.peer == peer) return;
        if (peers.remove(peer.nodeId(), peer)) {
            // No Call can register before the first successful handshake.
            peer.retire();
        }
    }

    RpcPeer requirePeer(int id) {
        var peer = peers.get(id);
        if (!running.getAsBoolean() || peer == null || peer.isClosed())
            throw RpcException.UNAVAILABLE;
        return peer;
    }

    private boolean isReadyConnection(RpcPeer peer, Connection connection) {
        var b = connection == null ? null : activeHandler(connection);
        return b != null && b.ready && b.peer == peer && connection.isActive();
    }

    private boolean allReady(RpcPeer peer) {
        if (peer.isClosed()) return false;
        for (int slot = 0; slot < peer.connectionCount(); slot++)
            if (!isReadyConnection(peer, peer.connection(slot))) return false;
        return true;
    }

    private void requireActivePeer(RpcPeer peer) {
        if (!running.getAsBoolean() || peer.isClosed()) throw RpcException.UNAVAILABLE;
    }

    /** Cheap rejection before encoding; do not advance round-robin or invoke custom selectors. */
    void preflight(RpcPeer peer, long key) {
        requireActivePeer(peer);
        if (selector == ConnectionSelector.DEFAULT && key != 0)
            requireWritable(peer, selector.select(peer, key));
        else findWritable(peer, 0);
    }

    private Connection findWritable(RpcPeer peer, int start) {
        boolean active = false;
        int slot = start;
        for (int i = 0; i < peer.connectionCount(); i++) {
            var c = peer.connection(slot);
            if (isReadyConnection(peer, c)) {
                active = true;
                if (c.isWritable()) {
                    requireActivePeer(peer);
                    return c;
                }
            }
            if (++slot == peer.connectionCount()) slot = 0;
        }
        throw active ? RpcException.OVERLOADED : RpcException.UNAVAILABLE;
    }

    private ConnectionHandler requireWritable(RpcPeer peer, Connection connection) {
        requireActivePeer(peer);
        var handler = activeHandler(connection);
        if (handler == null || !handler.ready || handler.peer != peer || !connection.isActive())
            throw RpcException.UNAVAILABLE;
        if (!connection.isWritable()) throw RpcException.OVERLOADED;
        return handler;
    }

    /** Select once after encoding, then validate the connection and custom selector output. */
    Connection selectOriginating(RpcPeer peer, long key) {
        requireActivePeer(peer);
        if (selector == ConnectionSelector.DEFAULT && key == 0)
            return findWritable(peer, peer.nextRoundRobin());
        var c = selector.select(peer, key);
        var handler = requireWritable(peer, c);
        if (peer.connection(handler.slot) != c) throw RpcException.UNAVAILABLE;
        return c;
    }

    /** Resolve the current connection within the verified Peer and Slot. */
    Connection selectReply(Connection connection) {
        var identity = Objects.requireNonNull(connection).get(identityKey);
        if (!running.getAsBoolean()
                || identity == null
                || !identity.ready
                || peers.get(identity.nodeId()) != identity.peer
                || identity.peer.isClosed()) throw RpcException.UNAVAILABLE;
        var current = connection.isActive() ? connection : identity.peer.connection(identity.slot);
        requireWritable(identity.peer, current);
        return current;
    }

    /** Revalidate after decoding; removed identities must not enter a new dispatch. */
    private boolean canReceive(ConnectionHandler b) {
        return running.getAsBoolean()
                && b.ready
                && b.registered
                && b.connection.isActive()
                && !b.peer.isClosed()
                && peers.get(b.nodeId()) == b.peer;
    }

    RuntimeException close(Consumer<RpcPeer> retiring, List<Runnable> notifications) {
        List<ConnectionHandler> closing;
        synchronized (lock) {
            for (var peer : peers.values()) {
                peer.retire();
                retiring.accept(peer);
                var notification =
                        connectNotification(
                                peer, null, new IllegalStateException("RpcNode closed"));
                if (notification != null) notifications.add(notification);
            }
            for (var plan : outbound.values()) plan.cancelAll();
            outbound.clear();
            closing = new ArrayList<>(handlers.values());
            handlers.clear();
            peers.clear();
            pendingHandshakes = 0;
            for (var b : closing) {
                b.registered = false;
                b.clearControl();
            }
        }
        return closeConnections(closing, notifications);
    }

    private RuntimeException closeConnections(
            List<ConnectionHandler> closing, List<Runnable> notifications) {
        RuntimeException failure = null;
        for (var b : closing) failure = closeConnection(b.connection, failure, notifications);
        return failure;
    }

    private RuntimeException closeConnection(
            Connection connection, RuntimeException failure, List<Runnable> notifications) {
        try {
            connection.close();
        } catch (RuntimeException e) {
            if (notifications == null)
                observe("connection-close-failed", null, null, connection, e);
            else
                notifications.add(
                        () -> observe("connection-close-failed", null, null, connection, e));
            if (failure == null) return e;
            if (failure != e) failure.addSuppressed(e);
        }
        return failure;
    }

    /** Detach under lock; the caller runs the notification on its current thread after cleanup. */
    private Runnable connectNotification(RpcPeer peer, Connection connection, Throwable failure) {
        var plan = outbound.get(peer);
        if (plan == null || plan.callbacks == null) return null;
        var callbacks = plan.callbacks;
        plan.callbacks = null;
        return () -> {
            for (var callback : callbacks) {
                try {
                    if (failure == null) callback.onSuccess(connection);
                    else callback.onFailure(failure);
                } catch (Throwable callbackFailure) {
                    observe(
                            "connect-callback-failed",
                            peer.nodeId(),
                            null,
                            connection,
                            callbackFailure);
                }
            }
        };
    }
}
