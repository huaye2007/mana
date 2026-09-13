package cn.managame.rpc.core;

import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcChecks;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.rpc.protocol.RpcLimits;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.transport.RpcNetworkConfig;
import cn.managame.rpc.transport.RpcNetworkProvider;
import cn.managame.rpc.transport.RpcTransport;

import cn.managame.network.*;

import io.netty.buffer.*;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.*;

/** Public RPC facade and owner of component startup/shutdown. */
public final class RpcNode implements AutoCloseable {
    private enum State {
        NEW,
        STARTING,
        RUNNING,
        CLOSED
    }

    private volatile State state = State.NEW;
    private final Object startup = new Object();
    private final RpcConnections connections;
    final RpcCalls calls;
    private final RpcMessages messages;
    private final int nodeId;
    private final Consumer<RpcDiagnostic> diagnostics;
    // Node-owned runtime resources, borrowed by the internal connection manager.
    final Timer timer;
    private final boolean ownsTimer;
    // Assigned by the owned timer factory during construction; never changes afterward.
    private Thread timerThread;
    private final RpcTransport transport;
    private final ConcurrentHashMap<String, LongAdder> events = new ConcurrentHashMap<>();

    private RpcNode(Builder b) {
        nodeId = Objects.requireNonNull(b.nodeId, "nodeId");
        if (b.maxPeers <= 0) throw new IllegalArgumentException("maxPeers");
        if (b.maxPendingHandshakes <= 0) throw new IllegalArgumentException("maxPendingHandshakes");
        String listenHost = Objects.requireNonNull(b.host, "listen address");
        if (listenHost.isBlank() || b.port < 0 || b.port > 65535)
            throw new IllegalArgumentException("listen address");
        if (b.connections <= 0 || b.maxConnections < b.connections)
            throw new IllegalArgumentException("connection counts");
        var limits = Objects.requireNonNull(b.limits);
        var defaultTimeout = Objects.requireNonNull(b.timeout, "defaultTimeout");
        RpcChecks.timeoutNanos(defaultTimeout);
        long reconnectNanos = RpcChecks.timeoutNanos(b.reconnect);
        if (b.maxReconnect != null && RpcChecks.timeoutNanos(b.maxReconnect) < reconnectNanos)
            throw new IllegalArgumentException("maxReconnectDelay must be >= reconnectDelay");
        RpcChecks.timeoutNanos(b.handshake);
        if (!b.readIdle.isZero()) RpcChecks.timeoutNanos(b.readIdle);
        if (RpcChecks.timeoutNanos(b.heartbeatInterval) < TimeUnit.MILLISECONDS.toNanos(100)
                || RpcChecks.timeoutNanos(b.heartbeatTimeout) < TimeUnit.MILLISECONDS.toNanos(100))
            throw new IllegalArgumentException("Heartbeat durations must be at least 100 ms");
        var handler = Objects.requireNonNull(b.handler, "handler");
        diagnostics = b.diagnostics;
        ownsTimer = b.timer == null;
        timer = ownsTimer ? newTimer(nodeId) : b.timer;
        RpcTransport created = null;
        try {
            RpcNetworkProvider provider = b.provider == null ? defaultProvider() : b.provider;
            transport =
                    created =
                            Objects.requireNonNull(
                                    provider.create(
                                            new RpcNetworkConfig(
                                                    nodeId,
                                                    InetSocketAddress.createUnresolved(
                                                            listenHost, b.port),
                                                    limits.maxMessageBytes(),
                                                    b.consolidateFlush,
                                                    b.readIdle)),
                                    "provider transport");
            var allocator = Objects.requireNonNull(transport.allocator(), "provider allocator");
            var codecHandler =
                    new RpcCodecHandler(
                            b.codec != null ? b.codec : new DefaultRpcCodec(allocator, limits),
                            limits);
            connections =
                    new RpcConnections(
                            nodeId,
                            limits.maxPendingCalls(),
                            b,
                            timer,
                            transport,
                            this::isRunning,
                            this::isClosed,
                            this::observe);
            calls = new RpcCalls(timer, transport, defaultTimeout, this::isRunning, this::observe);
            messages =
                    new RpcMessages(
                            connections,
                            calls,
                            transport,
                            codecHandler,
                            limits,
                            handler,
                            this::observe);
        } catch (RuntimeException | Error failure) {
            if (created != null) {
                try {
                    created.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            if (ownsTimer) {
                try {
                    timer.stop();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private Timer newTimer(int id) {
        return new HashedWheelTimer(
                task -> {
                    timerThread =
                            Thread.ofPlatform()
                                    .daemon()
                                    .name("rpc-" + id + "-timer")
                                    .unstarted(task);
                    return timerThread;
                },
                100,
                TimeUnit.MILLISECONDS,
                512);
    }

    private static RpcNetworkProvider defaultProvider() {
        var providers = ServiceLoader.load(RpcNetworkProvider.class).iterator();
        if (!providers.hasNext())
            throw new IllegalStateException("No RPC network provider; add game-rpc-netty");
        var provider = providers.next();
        if (providers.hasNext())
            throw new IllegalStateException("Multiple providers; configure one explicitly");
        return provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int nodeId() {
        return nodeId;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isClosed() {
        return state == State.CLOSED;
    }

    public InetSocketAddress localAddress() {
        return transport.localAddress();
    }

    public RpcPeer peer(int id) {
        return connections.peer(id);
    }

    /** Verified directly connected peer. */
    public RpcPeer peer(Connection connection) {
        return connections.peer(connection);
    }

    public Map<String, Long> eventCounts() {
        var copy = new HashMap<String, Long>();
        events.forEach((name, n) -> copy.put(name, n.sum()));
        return Map.copyOf(copy);
    }

    private void checkLifecycleThread() {
        if (Thread.holdsLock(startup))
            throw new IllegalStateException("Synchronous RPC lifecycle cannot reenter startup");
        if (Thread.currentThread() == timerThread)
            throw new IllegalStateException(
                    "Synchronous RPC lifecycle cannot run on the node time wheel");
        transport.checkLifecycleThread();
    }

    public void start() {
        checkLifecycleThread();
        try {
            synchronized (startup) {
                if (state == State.RUNNING) return;
                if (state != State.NEW)
                    throw new IllegalStateException("RpcNode cannot be started");
                state = State.STARTING;
                transport.start(
                        new RpcTransport.Listener() {
                            public NetworkHandler newInboundHandler() {
                                return connections.newHandler(messages, true);
                            }

                            public NetworkHandler newOutboundHandler() {
                                return connections.newHandler(messages, false);
                            }

                            public void onWriteFailure(Connection connection, Throwable failure) {
                                observe("write-failed", null, null, connection, failure);
                            }
                        });
                state = State.RUNNING;
            }
        } catch (RuntimeException | Error failure) {
            // Leave the startup monitor before cleanup can wait on network/timer threads.
            try {
                close();
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public void connect(int id, String host, int port) {
        connections.connect(id, host, port, null);
    }

    public void connect(int id, String host, int port, int count) {
        connections.connect(id, host, port, count, null);
    }

    /** Connect using the default slot count; see the explicit-count callback overload. */
    public void connect(int id, String host, int port, ConnectCallback callback) {
        connections.connect(id, host, port, Objects.requireNonNull(callback));
    }

    /**
     * Notify once on the completing thread after all slots are ready, passing the last ready
     * connection (slot zero if already ready). Network failures retry; removal, close or direction
     * conflict fails pending callbacks. Later reconnects do not repeat a notification. Callback
     * exceptions are reported through diagnostics, and notifications hold no connection management
     * lock. Business-thread dispatch belongs to the callback; RPC does not schedule callbacks on a
     * pool. Invalid state or configuration throws synchronously without invoking the callback. At
     * most 64 callbacks may wait per Peer; additional registrations throw OVERLOADED.
     */
    public void connect(int id, String host, int port, int count, ConnectCallback callback) {
        connections.connect(id, host, port, count, Objects.requireNonNull(callback));
    }

    public void removePeer(int id) {
        var closing = new LinkedHashMap<RpcPeer, List<RpcFuture>>();
        var notifications = new ArrayList<Runnable>();
        try {
            var failure =
                    connections.removePeer(
                            id, peer -> closing.put(peer, calls.settlePeer(peer)), notifications);
            if (failure != null) throw failure;
        } finally {
            calls.notifyClosed(closing);
            notifications.forEach(Runnable::run);
        }
    }

    /**
     * Stop new outbound calls and settle pending calls with UNAVAILABLE, without closing connections.
     * Replies to accepted inbound requests remain available. Irreversible and safe to repeat.
     * Callbacks run on this caller outside internal locks and must return promptly. A concurrent
     * response/timeout may already own a completion; this method does not wait for that callback.
     */
    public void stopCalls() { calls.stopCalls(connections::peersSnapshot); }

    /** Borrow body until return; the callback receives the complete raw response. */
    public void call(
            int target, int command, ByteBuf body, RpcOptions options, RpcCallback callback) {
        messages.call(target, command, body, options, callback);
    }

    public boolean send(int target, int command, ByteBuf body, RpcOptions options) {
        return messages.send(target, command, body, options);
    }

    /** Send an explicit message to a direct Peer; only routeKey is read from options. */
    public boolean send(int target, RpcMessage message, RpcOptions options) {
        return messages.send(target, message, options);
    }

    /** Reply using the verified connection identity; caller supplies the response requestId. */
    public boolean reply(Connection connection, RpcMessage message) {
        return messages.reply(connection, message);
    }

    private void observe(
            String event, Integer peer, Integer requestId, Connection c, Throwable failure) {
        events.computeIfAbsent(event, k -> new LongAdder()).increment();
        if (diagnostics != null)
            notifyDiagnostic(
                    new RpcDiagnostic(
                            event, nodeId, peer, requestId, c == null ? null : c.id(), failure));
    }

    private void notifyDiagnostic(RpcDiagnostic diagnostic) {
        try {
            diagnostics.accept(diagnostic);
        } catch (Throwable ignored) {
            /* Diagnostics must not interrupt cleanup or recurse. */
        }
    }

    /**
     * The first closer performs synchronous cleanup; concurrent/reentrant close calls are no-ops.
     */
    @Override
    public void close() {
        checkLifecycleThread();
        synchronized (startup) {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
        }
        var closing = new LinkedHashMap<RpcPeer, List<RpcFuture>>();
        var notifications = new ArrayList<Runnable>();
        RuntimeException failure = null;
        try {
            try {
                failure =
                        connections.close(
                                peer -> closing.put(peer, calls.settlePeer(peer)), notifications);
            } finally {
                try {
                    transport.close();
                } catch (RuntimeException e) {
                    if (failure == null) failure = e;
                    else if (failure != e) failure.addSuppressed(e);
                } finally {
                    try {
                        if (ownsTimer) timer.stop();
                    } catch (RuntimeException e) {
                        if (failure == null) failure = e;
                        else if (failure != e) failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) throw failure;
        } finally {
            // Callbacks may block or reenter the node; hold no lifecycle lock or network resource.
            calls.notifyClosed(closing);
            notifications.forEach(Runnable::run);
        }
    }

    public static final class Builder {
        private Integer nodeId;
        private String host;
        private int port;
        int connections = 1, maxConnections = 64, maxPeers = 4096;
        int maxPendingHandshakes = 1024;
        boolean consolidateFlush;
        private RpcLimits limits = RpcLimits.DEFAULT;
        Duration timeout, reconnect = Duration.ofSeconds(1), handshake = Duration.ofSeconds(5);
        Duration maxReconnect;
        Duration readIdle = Duration.ZERO;
        Duration heartbeatInterval = Duration.ofSeconds(5),
                heartbeatTimeout = Duration.ofSeconds(15);
        private RpcHandler handler;
        ConnectionSelector selector = ConnectionSelector.DEFAULT;
        IntPredicate admission = id -> true;
        private Consumer<RpcDiagnostic> diagnostics;
        private Timer timer;
        private RpcCodec codec;
        private RpcNetworkProvider provider;

        private Builder() {}

        public Builder nodeId(int v) {
            nodeId = v;
            return this;
        }

        public Builder listen(String host, int port) {
            this.host = host;
            this.port = port;
            return this;
        }

        /**
         * Replace the envelope format for all messages, including routes, handshakes and
         * heartbeats.
         */
        public Builder codec(RpcCodec v) {
            codec = Objects.requireNonNull(v);
            return this;
        }

        public Builder handler(RpcHandler v) {
            handler = Objects.requireNonNull(v);
            return this;
        }

        public Builder defaultTimeout(Duration v) {
            timeout = Objects.requireNonNull(v);
            return this;
        }

        /**
         * Initial retry ceiling; each retry waits between half and the full ceiling. Default: 1 s.
         */
        public Builder reconnectDelay(Duration v) {
            reconnect = Objects.requireNonNull(v);
            return this;
        }

        /** Backoff ceiling; defaults to the greater of 30 s and reconnectDelay. */
        public Builder maxReconnectDelay(Duration value) {
            maxReconnect = Objects.requireNonNull(value);
            return this;
        }

        /** Accepted TCP connections only; zero disables read-idle expiry (default). */
        public Builder readIdleTimeout(Duration value) {
            readIdle = Objects.requireNonNull(value);
            return this;
        }

        public Builder handshakeTimeout(Duration v) {
            handshake = Objects.requireNonNull(v);
            return this;
        }

        /**
         * Delay before the first probe and between successful probes. Default: 5 s; minimum: 100
         * ms.
         */
        public Builder heartbeatInterval(Duration value) {
            heartbeatInterval = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Maximum wait for a matching PONG, including encoding/write delay. Default: 15 s; minimum:
         * 100 ms.
         */
        public Builder heartbeatTimeout(Duration value) {
            heartbeatTimeout = Objects.requireNonNull(value);
            return this;
        }

        public Builder connectionsPerPeer(int v) {
            connections = v;
            return this;
        }

        public Builder maxConnectionsPerPeer(int v) {
            maxConnections = v;
            return this;
        }

        /** Maximum registered directly connected peers. Default: 4096. */
        public Builder maxPeers(int v) {
            maxPeers = v;
            return this;
        }

        /** Maximum simultaneous inbound and outbound RPC handshakes. Default: 1024. */
        public Builder maxPendingHandshakes(int v) {
            maxPendingHandshakes = v;
            return this;
        }

        /** Default false: immediate flush. Enable to coalesce bursts on each TCP EventLoop. */
        public Builder consolidateFlush(boolean v) {
            consolidateFlush = v;
            return this;
        }

        public Builder limits(RpcLimits v) {
            limits = Objects.requireNonNull(v);
            return this;
        }

        public Builder selector(ConnectionSelector v) {
            selector = Objects.requireNonNull(v);
            return this;
        }

        public Builder peerAdmission(IntPredicate v) {
            admission = Objects.requireNonNull(v);
            return this;
        }

        public Builder diagnostics(Consumer<RpcDiagnostic> v) {
            diagnostics = Objects.requireNonNull(v);
            return this;
        }

        public Builder timer(Timer v) {
            timer = Objects.requireNonNull(v);
            return this;
        }

        public Builder provider(RpcNetworkProvider v) {
            provider = Objects.requireNonNull(v);
            return this;
        }

        public RpcNode build() {
            return new RpcNode(this);
        }
    }
}
