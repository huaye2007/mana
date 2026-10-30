package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;

import cn.managame.Metadata;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkServer;
import cn.managame.network.netty.NetworkTransports;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * This process among the nodes of a game server cluster, over TCP; a process that talks to no other needs none.
 * Every node may dial others; a node that listens may also be dialed. Between two nodes only one side dials, and
 * once connected both sides call and notify each other alike:
 *
 * <pre>{@code
 * RpcNode node = RpcNode.builder()
 *         .nodeId(101).metadata(about).listen(7101)       // listening is optional
 *         .handler(handler).listener(listener)
 *         .start();
 *
 * RpcPeer world = node.createPeer(1, new InetSocketAddress("10.0.0.1", 7001));
 * world.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> ...);
 * }</pre>
 *
 * <p>The application drives membership: it creates the peers this node dials and removes peers that left, from its
 * own configuration or service discovery. Peers that dial in join by the id in their handshake, one {@link RpcPeer}
 * for each node, whichever side dialed. What a node is for, such as a game or a world server, is the application's
 * to put in the handshake {@link Builder#metadata}, which every peer reads as {@link RpcPeer#metadata()}. Going
 * offline as planned is the application's too: a node tells its peers it is leaving, in a notify or through
 * service discovery, finishes its work and then closes.
 *
 * <p>Each connection starts with both sides sending a handshake with the protocol version, node id,
 * {@link #incarnation()} and metadata, the dialing side first, naming also the slot of the connection among those
 * it opens. A connection is refused when the two speak different protocol versions, the dialed node is not the one
 * meant, the dialing node is this node or one this node dials itself, the {@link RpcAuthenticator} says no, or an
 * older instance of a peer dials while a newer one is connected. Then the dialing side sends a heartbeat every
 * interval, which the other side answers; a side that receives nothing for three of its own intervals, as on a
 * connection to a machine that went down without closing it, closes the connection, and the dialing side dials it
 * again. Nodes of a cluster use the same heartbeat interval and largest frame.
 *
 * <p>One time wheel, ticking every 10 milliseconds, does all of a node's timing: call timeouts and heartbeats.
 */
public final class RpcNode implements AutoCloseable {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_FRAME_LENGTH = 8 * 1024 * 1024;
    public static final int DEFAULT_MAX_PENDING_CALLS = 4096;
    /** How much a connection may queue before calls and notifies are refused; set unless the bootstraps do. */
    public static final WriteBufferWaterMark DEFAULT_WRITE_BUFFER_WATER_MARK = new WriteBufferWaterMark(1 << 20, 4 << 20);
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    private static final long TICK_MILLIS = 10;
    private static final RpcHandler NO_HANDLER = (rpcPeer, connection, msg) -> {
        if (msg instanceof RpcRequest request) RpcResponse.failure(request.requestId(), HANDLER_NOT_FOUND).writeTo(connection);
    };
    private final int nodeId;
    private final long incarnation;
    private final Metadata metadata;
    private final RpcListener listener;
    private final Duration timeout, redialMin, redialMax;
    private final int maxFrameLength, maxPendingCalls;
    private final ConcurrentHashMap<Integer, RpcPeer> peers = new ConcurrentHashMap<>();
    /** Every connection, dialed or accepted, to close them all on close whoever owns their EventLoops. */
    private final ChannelGroup channels = new DefaultChannelGroup("rpc", GlobalEventExecutor.INSTANCE);
    private final HashedWheelTimer timer;
    /** The EventLoops this node created and shuts down on close. */
    private final List<EventLoopGroup> ownedGroups = new ArrayList<>();
    /** Every EventLoop connections run on, where nothing may block. */
    private final List<EventLoopGroup> loops = new ArrayList<>();
    /** The EventLoops this node dials on, which run the callbacks of calls that fail before they are sent. */
    private final EventExecutor[] dialingLoops;
    private final Bootstrap dialing;
    private final NetworkServer server;
    private final Object lifecycle = new Object();
    private volatile boolean closed;

    private RpcNode(Builder b) {
        nodeId = b.nodeId;
        incarnation = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
        metadata = b.metadata;
        listener = b.listener;
        timeout = b.timeout;
        redialMin = b.redialMin;
        redialMax = b.redialMax;
        maxFrameLength = b.maxFrameLength;
        maxPendingCalls = b.maxPendingCalls;
        timer = new HashedWheelTimer(Thread.ofPlatform().name("rpc-timer-" + nodeId).daemon().factory(), TICK_MILLIS, TimeUnit.MILLISECONDS);
        RpcConnectionHandler connections = new RpcConnectionHandler(this, b.handler, b.authenticator);
        long heartbeatMillis = b.heartbeatInterval.toMillis();
        EventLoopGroup shared = null;
        Bootstrap client = b.bootstrap.clone().handler(initializer(false, heartbeatMillis, connections));
        if (client.config().group() == null) client.group(shared = owned(NetworkTransports.newEventLoopGroup(0, "rpc-io")));
        if (!client.config().options().containsKey(ChannelOption.WRITE_BUFFER_WATER_MARK))
            client.option(ChannelOption.WRITE_BUFFER_WATER_MARK, DEFAULT_WRITE_BUFFER_WATER_MARK);
        if (!client.config().options().containsKey(ChannelOption.MESSAGE_SIZE_ESTIMATOR))
            client.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, RpcCodec.SIZES);
        loops.add(client.config().group());
        List<EventExecutor> executors = new ArrayList<>();
        client.config().group().forEach(executors::add);
        dialingLoops = executors.toArray(EventExecutor[]::new);
        dialing = client;
        if (b.listen == null) {
            server = null;
            return;
        }
        ServerBootstrap accepting = b.serverBootstrap.clone().childHandler(initializer(true, heartbeatMillis, connections));
        if (accepting.config().group() == null)
            accepting.group(owned(NetworkTransports.newEventLoopGroup(1, "rpc-accept")),
                    shared != null ? shared : owned(NetworkTransports.newEventLoopGroup(0, "rpc-io")));
        if (!accepting.config().childOptions().containsKey(ChannelOption.WRITE_BUFFER_WATER_MARK))
            accepting.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, DEFAULT_WRITE_BUFFER_WATER_MARK);
        if (!accepting.config().childOptions().containsKey(ChannelOption.MESSAGE_SIZE_ESTIMATOR))
            accepting.childOption(ChannelOption.MESSAGE_SIZE_ESTIMATOR, RpcCodec.SIZES);
        loops.add(accepting.config().childGroup());
        try {
            server = NetworkServer.start(accepting, b.listen);
        } catch (RuntimeException e) {
            timer.stop();
            ownedGroups.forEach(RpcNode::shutdown);
            throw e;
        }
    }

    /** Every connection's pipeline: flush consolidation, then the codec. */
    private ConnectionInitializer initializer(boolean accepting, long heartbeatMillis, RpcConnectionHandler connections) {
        return ConnectionInitializer.tcp()
                .addLast("rpc-flush", () -> new FlushConsolidationHandler(FlushConsolidationHandler.DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES, true))
                .addLast("rpc-codec", () -> new RpcCodec(maxFrameLength, timer, heartbeatMillis, !accepting))
                .customize(created -> channels.add(created.channel()))
                .handler(connections)
                .build();
    }

    private EventLoopGroup owned(EventLoopGroup group) {
        ownedGroups.add(group);
        return group;
    }

    public static Builder builder() { return new Builder(); }

    public int nodeId() { return nodeId; }

    /**
     * This running instance of the node, sent in every handshake so that peers tell a restart from a reconnect:
     * the start time in milliseconds times 1000, plus a random number below 1000.
     */
    public long incarnation() { return incarnation; }

    /** What this node sends in every handshake. */
    public Metadata metadata() { return metadata; }

    /** The listening port, or -1 when the node does not listen. */
    public int port() { return server == null ? -1 : server.port(); }

    /** {@link #createPeer(int, InetSocketAddress, int)} with one connection. */
    public RpcPeer createPeer(int nodeId, InetSocketAddress address) { return createPeer(nodeId, address, 1); }

    /**
     * Starts dialing node {@code nodeId} at {@code address} with {@code connections} connections, one per slot, and
     * returns its peer at once; calls to it fail with RPC_UNAVAILABLE until a connection completes its handshake.
     * Each connection is dialed again whenever it fails or closes, with the builder's redial delays. The node
     * answering must be {@code nodeId}, or the connection is closed and dialed again.
     *
     * @throws IllegalArgumentException for this node's own id, or connections outside 1 to 65535
     * @throws IllegalStateException    when the id is a peer already, dialed by this node or dialing in (two nodes
     *                                  must not dial each other; remove it first), or the node is closed
     */
    public RpcPeer createPeer(int nodeId, InetSocketAddress address, int connections) {
        Objects.requireNonNull(address, "address");
        if (nodeId == this.nodeId) throw new IllegalArgumentException("Node " + nodeId + " is this node");
        if (connections < 1 || connections > 65535) throw new IllegalArgumentException("connections must be 1 to 65535");
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("The node is closed");
            RpcPeer peer = new RpcPeer(this, nodeId, true, connections);
            RpcPeer known = peers.putIfAbsent(nodeId, peer);
            if (known != null)
                throw new IllegalStateException("Node " + nodeId + " is a peer already, " + (known.isOutbound() ? "dialed by this node" : "dialing in"));
            Bootstrap bootstrap = dialing.clone().attr(RpcConnectionHandler.DIALED, peer);
            List<NetworkClient> clients = new ArrayList<>(connections);
            for (int slot = 0; slot < connections; slot++)
                clients.add(NetworkClient.connect(bootstrap.clone().attr(RpcConnectionHandler.SLOT, slot), address, redialMin, redialMax));
            peer.dial(clients);
            return peer;
        }
    }

    /**
     * Forgets node {@code nodeId}, dialed or dialing in: fails its waiting calls with RPC_UNAVAILABLE, on this
     * thread, stops dialing it and closes its connections. A node that dials in again afterwards joins as a new
     * peer. False when there was no such peer. Blocks until the connections are closed, so it must not run on the
     * node's EventLoops, where its handler, listener and answered callbacks run (IllegalStateException).
     */
    public boolean removePeer(int nodeId) {
        checkNotOnEventLoop();
        RpcPeer peer = peers.remove(nodeId);
        if (peer == null) return false;
        peer.remove();
        return true;
    }

    /** The peer with this id, dialed or dialing in, connected or not; null when there is none. */
    public RpcPeer peer(int nodeId) { return peers.get(nodeId); }

    /** A snapshot of every peer. */
    public Collection<RpcPeer> peers() { return List.copyOf(peers.values()); }

    /**
     * Sends {@code notify} to each of {@code peers}, as {@link RpcPeer#notify(RpcNotify)} does, sharing its body,
     * which is not copied, and releases it afterwards. Returns how many peers' connections accepted it.
     *
     * @throws IllegalArgumentException when the notify is larger than the largest frame, after releasing it
     */
    public int broadcast(Collection<RpcPeer> peers, RpcNotify notify) {
        Objects.requireNonNull(notify, "notify");
        int sent = 0;
        try {
            if (!RpcCodec.fits(notify, maxFrameLength))
                throw new IllegalArgumentException("Notify " + notify.command() + " is larger than the largest frame, " + maxFrameLength + " bytes");
            for (RpcPeer peer : peers) if (peer.notify(notify.retainedDuplicate()) == WriteStatus.ACCEPTED) sent++;
        } finally {
            notify.release();
        }
        return sent;
    }

    /**
     * Closes the node at once: stops listening and dialing, fails every waiting call with RPC_UNAVAILABLE, on this
     * thread, and closes every connection, so that peers see this node leave; calls made afterwards fail too, and
     * answers still to come no longer reach their callers. What was written before, such as answers, is flushed as
     * each connection closes; only what a busy connection's socket cannot take at that moment is lost. Blocks until
     * done; idempotent; must not run on the node's EventLoops or its timer (IllegalStateException).
     */
    @Override public void close() {
        checkNotOnEventLoop();
        timer.stop(); // first: on the timer's own thread it throws IllegalStateException before anything closed
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
        }
        try {
            if (server != null) server.close();
        } finally {
            for (RpcPeer peer : peers.values()) peer.close();
            channels.close().awaitUninterruptibly();
            ownedGroups.forEach(RpcNode::shutdown);
        }
    }

    @Override public String toString() { return "RpcNode[" + nodeId + "]"; }

    Duration timeout() { return timeout; }

    int maxFrameLength() { return maxFrameLength; }

    int maxPendingCalls() { return maxPendingCalls; }

    /** The time wheel for call timeouts; stopped once the node closes. */
    Timer timer() { return timer; }

    /** Closed: it takes no new peers, and calls fail. */
    boolean isClosed() { return closed; }

    /** The peer node {@code nodeId} joins when it dials in: the one known under its id, or a new one. */
    RpcPeer inboundPeer(int nodeId) { return peers.computeIfAbsent(nodeId, created -> new RpcPeer(this, created, false, 0)); }

    /**
     * Runs {@code task} soon on the dialing EventLoop {@code key} picks, or, once those have stopped, on a thread of
     * its own: never inside the caller.
     */
    void runOnEventLoop(long key, Runnable task) {
        try {
            dialingLoops[dialingLoops.length == 1 ? 0 : Math.floorMod(key, dialingLoops.length)].execute(task);
        } catch (RejectedExecutionException stopped) {
            Thread.ofVirtual().name("rpc-late-" + nodeId).start(task);
        }
    }

    void fireConnected(RpcPeer peer, boolean restarted) {
        if (listener == null) return;
        try {
            listener.onConnected(peer, restarted);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The RPC listener failed on " + peer + " connecting", e);
        }
    }

    void fireDisconnected(RpcPeer peer) {
        if (listener == null) return;
        try {
            listener.onDisconnected(peer);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The RPC listener failed on " + peer + " disconnecting", e);
        }
    }

    private void checkNotOnEventLoop() {
        for (EventLoopGroup group : loops)
            for (EventExecutor loop : group)
                if (loop.inEventLoop()) throw new IllegalStateException("Cannot block on the node's own EventLoop");
    }

    private static void shutdown(EventLoopGroup group) {
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    public static final class Builder {
        private Integer nodeId;
        private Metadata metadata = Metadata.empty();
        private InetSocketAddress listen;
        private RpcHandler handler = NO_HANDLER;
        private RpcListener listener;
        private RpcAuthenticator authenticator;
        private Bootstrap bootstrap = new Bootstrap();
        private ServerBootstrap serverBootstrap = new ServerBootstrap();
        private Duration timeout = DEFAULT_TIMEOUT, heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
        private Duration redialMin = NetworkClient.DEFAULT_MIN_RETRY, redialMax = NetworkClient.DEFAULT_MAX_RETRY;
        private int maxFrameLength = DEFAULT_MAX_FRAME_LENGTH, maxPendingCalls = DEFAULT_MAX_PENDING_CALLS;

        private Builder() {}

        /** Required: this node's id, unique in the cluster, which its peers know it by. */
        public Builder nodeId(int id) { nodeId = id; return this; }

        /** What this node tells every peer in the handshake, such as what it is for; empty by default. */
        public Builder metadata(Metadata metadata) { this.metadata = Objects.requireNonNull(metadata, "metadata"); return this; }

        /** Lets other nodes dial this one on {@code port} of all interfaces; a node that only dials need not listen. */
        public Builder listen(int port) { return listen(new InetSocketAddress(port)); }

        /** Lets other nodes dial this one on {@code address}; port 0 binds an ephemeral port, see {@link #port}. */
        public Builder listen(InetSocketAddress address) { listen = Objects.requireNonNull(address, "address"); return this; }

        /** Handles what peers send. Without one, requests fail with HANDLER_NOT_FOUND and notifies are dropped. */
        public Builder handler(RpcHandler handler) { this.handler = Objects.requireNonNull(handler, "handler"); return this; }

        /** Hears peers come, go and restart; none by default. */
        public Builder listener(RpcListener listener) { this.listener = Objects.requireNonNull(listener, "listener"); return this; }

        /** Admits the nodes on the other side of each connection; all by default. */
        public Builder authenticator(RpcAuthenticator authenticator) {
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
            return this;
        }

        /**
         * How this node dials: EventLoops, channel type and options, as Netty's own bootstrap, which must leave the
         * handler to the node. Unset, the node uses EventLoops of its own, shut down on close, and the channel type
         * that matches them; it sets {@link #DEFAULT_WRITE_BUFFER_WATER_MARK} and TCP_NODELAY unless set here, and
         * a MESSAGE_SIZE_ESTIMATOR that counts calls, notifies and answers by their size while they wait for their
         * EventLoop, unless one is set here.
         */
        public Builder bootstrap(Bootstrap bootstrap) {
            if (bootstrap.config().handler() != null) throw new IllegalArgumentException("The node sets the bootstrap's handler");
            this.bootstrap = bootstrap;
            return this;
        }

        /** How this node listens, as {@link #bootstrap} says for dialing; the child handler is the node's. */
        public Builder serverBootstrap(ServerBootstrap bootstrap) {
            if (bootstrap.config().childHandler() != null) throw new IllegalArgumentException("The node sets the bootstrap's child handler");
            serverBootstrap = bootstrap;
            return this;
        }

        /** How long a dialed connection waits before dialing again: from {@code min}, doubling up to {@code max}. */
        public Builder redialDelay(Duration min, Duration max) {
            if (min.toMillis() < 1 || max.compareTo(min) < 0)
                throw new IllegalArgumentException("Redial delays must be at least one millisecond, the maximum no less than the minimum");
            redialMin = min;
            redialMax = max;
            return this;
        }

        /**
         * How long a call waits for its answer unless the call says otherwise; 5 seconds by default. Timeouts fire
         * on the node's time wheel, up to its 10 millisecond tick late.
         */
        public Builder timeout(Duration timeout) {
            if (timeout.toMillis() < 1) throw new IllegalArgumentException("timeout must be at least one millisecond");
            this.timeout = timeout;
            return this;
        }

        /**
         * How often a connection this node dials sends a heartbeat, which the other side answers; 5 seconds by
         * default. Either side closes a connection on which it received nothing for three of its own intervals, so
         * the nodes of a cluster use the same interval.
         */
        public Builder heartbeatInterval(Duration interval) {
            if (interval.toMillis() < 1) throw new IllegalArgumentException("heartbeatInterval must be at least one millisecond");
            heartbeatInterval = interval;
            return this;
        }

        /**
         * The largest frame this node sends and receives, its 4-byte length included; 8 MiB by default. A frame
         * larger arriving closes the connection, so the nodes of a cluster use the same value.
         */
        public Builder maxFrameLength(int bytes) {
            if (bytes < RpcCodec.MIN_FRAME_LENGTH) throw new IllegalArgumentException("maxFrameLength must be at least " + RpcCodec.MIN_FRAME_LENGTH);
            maxFrameLength = bytes;
            return this;
        }

        /**
         * How many calls to one peer may wait for their answer at a time; further calls fail with RPC_UNAVAILABLE
         * until some end. 4096 by default. Each peer has a table of a reference per call allowed.
         */
        public Builder maxPendingCalls(int calls) {
            if (calls < 1 || calls > 1 << 30) throw new IllegalArgumentException("maxPendingCalls must be 1 to 2^30");
            maxPendingCalls = calls;
            return this;
        }

        /** Listens, when asked to, and returns the running node. A bind failure throws NetworkException. */
        public RpcNode start() {
            if (nodeId == null) throw new IllegalStateException("nodeId is required");
            return new RpcNode(this);
        }
    }
}
