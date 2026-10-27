package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkHandlers;
import cn.managame.network.netty.NetworkServer;
import cn.managame.network.netty.NetworkTransports;
import cn.managame.rpc.RpcFrames.Goodbye;
import cn.managame.rpc.RpcFrames.Hello;
import cn.managame.rpc.RpcFrames.Ping;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This process among the nodes of a game server cluster. Every node may dial others; a node that listens may also
 * be dialed. Between two nodes only one side dials, and once connected both sides call and notify each other
 * alike:
 *
 * <pre>{@code
 * RpcNode node = RpcNode.builder()
 *         .nodeId(101).metadata(hello).listen(7101)       // listening is optional
 *         .handler(handler).listener(listener)
 *         .start();
 *
 * RpcPeer world = node.createPeer(1, new InetSocketAddress("10.0.0.1", 7001));
 * world.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> ...);
 * }</pre>
 *
 * <p>The application drives membership: it creates the peers this node dials and removes peers that left, from its
 * own configuration or service discovery. Peers that dial in join by the id in their handshake. What a node is
 * for, such as a game or a world server, is the application's to put in the handshake {@link Builder#metadata},
 * which every peer reads as {@link RpcPeer#metadata()}.
 *
 * <p>Connections are network's plain connections, with the frame codec in their pipeline. Each starts with both
 * sides sending a Hello with the protocol versions they speak, node id, {@link #incarnation()} and metadata, the
 * dialing side first. A connection is refused when the two share no protocol version, the dialed node is not the
 * one meant, the dialing node is this node or one this node dials itself, the {@link RpcAuthenticator} says no,
 * or an older instance of a peer dials while a newer one is connected. Either side of a connection that has
 * written nothing for a third of the idle timeout sends a Ping; one that has received nothing for the whole idle
 * timeout, such as one to a machine that went down without closing it, is closed, failing its calls at once, and
 * redialed when this node dialed it.
 */
public final class RpcNode implements AutoCloseable {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_FRAME_LENGTH = 8 * 1024 * 1024;
    /** How much a connection may queue before calls and notifies are refused; set unless the bootstraps do. */
    public static final WriteBufferWaterMark DEFAULT_WRITE_BUFFER_WATER_MARK = new WriteBufferWaterMark(1 << 20, 4 << 20);
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    /** The peer a connection was dialed to; unset on connections peers dial in. */
    private static final AttributeKey<RpcPeer> DIALED = AttributeKey.valueOf("cn.managame.rpc.dialed");
    /** The address a connection was dialed to. */
    private static final AttributeKey<SocketAddress> ADDRESS = AttributeKey.valueOf("cn.managame.rpc.address");
    /** The ids of the requests received on a connection and not answered yet; touched on its EventLoop only. */
    private static final AttributeKey<Set<Integer>> UNANSWERED = AttributeKey.valueOf("cn.managame.rpc.unanswered");
    private static final RpcHandler NO_HANDLER = (rpcPeer, connection, msg) -> {
        if (msg instanceof RpcRequest request) RpcResponse.failure(request.requestId(), HANDLER_NOT_FOUND).writeTo(connection);
    };
    private final int nodeId;
    private final long incarnation;
    private final Metadata metadata;
    private final RpcHandler handler;
    private final RpcListener listener;
    private final RpcAuthenticator authenticator;
    private final RpcObserver observer;
    private final Duration timeout, idleTimeout, redialMin, redialMax;
    private final int maxFrameLength;
    private final ConcurrentHashMap<Integer, RpcPeer> peers = new ConcurrentHashMap<>();
    private final AtomicInteger requestIds = new AtomicInteger(), unanswered = new AtomicInteger();
    /** Every connection, dialed or accepted, to close them all on close whoever owns their EventLoops. */
    private final ChannelGroup channels = new DefaultChannelGroup("rpc", GlobalEventExecutor.INSTANCE);
    private final HashedWheelTimer timer;
    private volatile Thread timerThread;
    /** The EventLoops this node created and shuts down on close. */
    private final List<EventLoopGroup> ownedGroups = new ArrayList<>();
    /** Every EventLoop connections run on, where nothing may block. */
    private final List<EventLoopGroup> loops = new ArrayList<>();
    private final Bootstrap dialing;
    private final NetworkServer server;
    private final int port;
    private final Object lifecycle = new Object();
    private volatile boolean closing, closed;

    private RpcNode(Builder b) {
        nodeId = b.nodeId;
        incarnation = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
        metadata = b.metadata;
        handler = b.handler;
        listener = b.listener;
        authenticator = b.authenticator;
        observer = b.observer;
        timeout = b.timeout;
        idleTimeout = b.idleTimeout;
        redialMin = b.redialMin;
        redialMax = b.redialMax;
        maxFrameLength = b.maxFrameLength;
        timer = new HashedWheelTimer(task -> timerThread = Thread.ofPlatform().name("rpc-timer-" + nodeId).daemon().unstarted(task),
                10, TimeUnit.MILLISECONDS);
        Frames frames = new Frames();
        EventLoopGroup shared = null;
        Bootstrap client = b.bootstrap.clone().handler(initializer(b, false, frames));
        if (client.config().group() == null) client.group(shared = own(NetworkTransports.newEventLoopGroup(0, "rpc-io")));
        if (!client.config().options().containsKey(ChannelOption.WRITE_BUFFER_WATER_MARK))
            client.option(ChannelOption.WRITE_BUFFER_WATER_MARK, DEFAULT_WRITE_BUFFER_WATER_MARK);
        loops.add(client.config().group());
        dialing = client;
        if (b.listen == null) {
            server = null;
            port = -1;
            return;
        }
        ServerBootstrap accepting = b.serverBootstrap.clone().childHandler(initializer(b, true, frames));
        if (accepting.config().group() == null)
            accepting.group(own(NetworkTransports.newEventLoopGroup(1, "rpc-accept")),
                    shared != null ? shared : own(NetworkTransports.newEventLoopGroup(0, "rpc-io")));
        if (!accepting.config().childOptions().containsKey(ChannelOption.WRITE_BUFFER_WATER_MARK))
            accepting.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, DEFAULT_WRITE_BUFFER_WATER_MARK);
        loops.add(accepting.config().childGroup());
        try {
            server = NetworkServer.start(accepting, b.listen);
        } catch (RuntimeException e) {
            timer.stop();
            ownedGroups.forEach(RpcNode::shutdown);
            throw e;
        }
        port = b.listen instanceof InetSocketAddress ? server.port() : -1;
    }

    /**
     * Every connection's pipeline: TLS when configured, then flush consolidation, idle detection first among the
     * rest so that any bytes received count, the framing and the frames.
     */
    private ConnectionInitializer initializer(Builder b, boolean accepting, Frames frames) {
        long idle = b.idleTimeout.toNanos();
        int maxFrame = b.maxFrameLength;
        ConnectionInitializer.Builder pipeline = ConnectionInitializer.tcp();
        SslContext clientSsl = b.clientSsl;
        if (accepting && b.serverSsl != null) pipeline.ssl(b.serverSsl);
        if (!accepting && clientSsl != null) pipeline.customize(created -> created.addFirst("rpc-tls", clientSsl(clientSsl, created.channel())));
        return pipeline
                .addLast("rpc-flush", () -> new FlushConsolidationHandler(FlushConsolidationHandler.DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES, true))
                .addLast("rpc-idle", () -> new IdleStateHandler(idle, idle / 3, 0, TimeUnit.NANOSECONDS))
                .addLast(NetworkHandlers.lengthFieldFraming(maxFrame))
                .addLast("rpc-frames", () -> new RpcFrames(this))
                .customize(created -> channels.add(created.channel()))
                .handler(frames)
                .build();
    }

    /** TLS toward the address dialed, so that the context can check the certificate names it. */
    private static SslHandler clientSsl(SslContext ssl, Channel channel) {
        return channel.attr(ADDRESS).get() instanceof InetSocketAddress address
                ? ssl.newHandler(channel.alloc(), address.getHostString(), address.getPort())
                : ssl.newHandler(channel.alloc());
    }

    private EventLoopGroup own(EventLoopGroup group) {
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

    /** The listening port, or -1 when the node does not listen on an IP address. */
    public int port() { return port; }

    /** {@link #createPeer(int, SocketAddress, int)} with one connection. */
    public RpcPeer createPeer(int nodeId, SocketAddress address) { return createPeer(nodeId, address, 1); }

    /**
     * Starts dialing node {@code nodeId} at {@code address} with {@code connections} connections and returns its
     * peer at once; calls to it fail with RPC_UNAVAILABLE until a connection completes its handshake. Each connection
     * is redialed whenever it fails or closes, with the builder's redial delays. The node answering must be
     * {@code nodeId}, or the connection is closed and redialed.
     *
     * @throws IllegalArgumentException for this node's own id, or fewer than one connection
     * @throws IllegalStateException    when the id is a peer already, dialed by this node or dialing in (two nodes
     *                                  must not dial each other; remove it first), or the node is closing
     */
    public RpcPeer createPeer(int nodeId, SocketAddress address, int connections) {
        Objects.requireNonNull(address, "address");
        if (nodeId == this.nodeId) throw new IllegalArgumentException("Node " + nodeId + " is this node");
        if (connections < 1) throw new IllegalArgumentException("connections must be positive");
        synchronized (lifecycle) {
            if (closing) throw new IllegalStateException("The node is closing");
            RpcPeer peer = new RpcPeer(this, nodeId, true);
            RpcPeer known = peers.putIfAbsent(nodeId, peer);
            if (known != null)
                throw new IllegalStateException("Node " + nodeId + " is a peer already, " + (known.isOutbound() ? "dialed by this node" : "dialing in"));
            Bootstrap bootstrap = dialing.clone().attr(DIALED, peer).attr(ADDRESS, address);
            List<NetworkClient> clients = new ArrayList<>(connections);
            for (int i = 0; i < connections; i++) clients.add(NetworkClient.connect(bootstrap, address, redialMin, redialMax));
            peer.dial(clients);
            return peer;
        }
    }

    /**
     * Forgets node {@code nodeId}, dialed or dialing in: fails its waiting calls, and later calls through the removed
     * peer, with RPC_PEER_REMOVED, stops dialing it and closes its connections. A node that dials in again afterwards
     * joins as a new peer. False when there was no such peer. Blocks until the connections are closed, so it must not
     * run on the node's EventLoops or timer, in its handler, listener or callbacks (IllegalStateException).
     */
    public boolean removePeer(int nodeId) {
        checkNotOnNodeThread();
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
     * which is not copied, and releases it afterwards. Returns how many peers it was sent to.
     */
    public int broadcast(Collection<RpcPeer> peers, RpcNotify notify) {
        Objects.requireNonNull(notify, "notify");
        int sent = 0;
        try {
            for (RpcPeer peer : peers) if (peer.notify(notify.retainedDuplicate())) sent++;
        } finally {
            notify.release();
        }
        return sent;
    }

    /** The requests peers sent to this node that it has neither answered nor seen expire. */
    public int unansweredCount() { return unanswered.get(); }

    /** {@link #close(Duration)} without draining. */
    @Override public void close() { close(Duration.ZERO); }

    /**
     * Closes the node, first draining it for up to {@code drain}: it stops listening and says Goodbye to every
     * peer, which then sends it nothing new, and waits until it has answered the requests peers sent it and its own
     * calls have settled, so that a rolling restart leaves no call in doubt. Its own calls still go out meanwhile.
     * Then it fails every waiting call, and calls made afterwards, with RPC_NODE_CLOSED, stops dialing and closes
     * every connection; answers still to come no longer reach their callers. Blocks until done; idempotent; must not
     * run on the node's EventLoops or timer (IllegalStateException).
     */
    public void close(Duration drain) {
        Objects.requireNonNull(drain, "drain");
        checkNotOnNodeThread();
        synchronized (lifecycle) {
            if (closing) return;
            closing = true;
        }
        try {
            if (server != null) server.close();
            if (drain.toNanos() > 0) drain(drain.toNanos());
        } finally {
            closed = true;
            for (RpcPeer peer : peers.values()) peer.close();
            channels.close().awaitUninterruptibly();
            timer.stop();
            ownedGroups.forEach(RpcNode::shutdown);
        }
    }

    private void drain(long nanos) {
        for (RpcPeer peer : peers.values()) peer.goodbye();
        long deadline = System.nanoTime() + nanos;
        try {
            while (System.nanoTime() - deadline < 0
                    && (unanswered.get() > 0 || peers.values().stream().anyMatch(RpcPeer::hasWaitingCalls)))
                Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public String toString() { return "RpcNode[" + nodeId + "]"; }

    Duration timeout() { return timeout; }

    int maxFrameLength() { return maxFrameLength; }

    /** Closed for good: calls fail with RPC_NODE_CLOSED. While it only drains, calls still go out. */
    boolean isClosed() { return closed; }

    int nextRequestId() {
        int id = requestIds.incrementAndGet();
        return id != 0 ? id : requestIds.incrementAndGet();
    }

    /** Runs {@code task} on the node's timer after {@code nanos}; null once the node has closed. */
    Timeout newTimeout(Runnable task, long nanos) {
        try {
            return timer.newTimeout(expired -> task.run(), nanos, TimeUnit.NANOSECONDS);
        } catch (IllegalStateException stopped) {
            return null;
        }
    }

    /** An answer to a request received on {@code channel} went out. */
    void answered(Channel channel, int requestId) {
        Set<Integer> waiting = channel.attr(UNANSWERED).get();
        if (waiting != null && waiting.remove(requestId)) unanswered.decrementAndGet();
    }

    void settled(RpcPeer peer, RpcRequest request, RpcResponse response, long elapsedNanos) {
        if (observer == null) return;
        try {
            observer.onCallSettled(peer, request, response, elapsedNanos);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The RPC observer failed", e);
        }
    }

    void connected(RpcPeer peer, boolean restarted) {
        if (listener == null) return;
        try {
            listener.onConnected(peer, restarted);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The RPC listener failed on " + peer + " connecting", e);
        }
    }

    void disconnected(RpcPeer peer) {
        if (listener == null) return;
        try {
            listener.onDisconnected(peer);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The RPC listener failed on " + peer + " disconnecting", e);
        }
    }

    void closing(RpcPeer peer) {
        if (listener == null) return;
        try {
            listener.onClosing(peer);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The RPC listener failed on " + peer + " closing", e);
        }
    }

    private void checkNotOnNodeThread() {
        if (Thread.currentThread() == timerThread) throw new IllegalStateException("Cannot block on the node's own timer");
        for (EventLoopGroup group : loops)
            for (EventExecutor loop : group)
                if (loop.inEventLoop()) throw new IllegalStateException("Cannot block on the node's own EventLoop");
    }

    private static void shutdown(EventLoopGroup group) {
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private static void refuse(Connection connection, String reason) {
        LOG.log(Level.WARNING, "Closing RPC connection " + connection + ": " + reason);
        connection.close();
    }

    /**
     * Every connection's events: the handshake, then the frames both ways, and the Pings. Received frames are
     * released by network after each call returns.
     */
    private final class Frames implements ConnectionHandler {
        /** The dialing side speaks first. */
        @Override public void onActive(Connection connection) {
            if (connection.get(DIALED) != null) hello(connection);
        }

        @Override public void onRead(Connection connection, Object frame) {
            RpcPeer peer = RpcPeer.of(connection);
            if (frame instanceof Hello hello) {
                handshake(connection, peer, hello);
            } else if (peer == null) {
                refuse(connection, "a frame came before the Hello");
            } else {
                switch (frame) {
                    case RpcResponse response -> peer.answer(response);
                    case RpcRequest request -> request(peer, connection, request);
                    case RpcNotify notify -> dispatch(peer, connection, notify);
                    case Goodbye goodbye -> peer.closing();
                    default -> {} // a Ping: receiving it was the point
                }
            }
        }

        /**
         * The other side named itself. Both must share a protocol version, and the authenticator must admit it. The
         * dialed node must be the one this node meant to reach. A node dialing in must not be this node or one this
         * node dials itself, since two nodes must not dial each other; it is answered once accepted and before it
         * joins, so this node's Hello precedes anything sent once it has joined.
         */
        private void handshake(Connection connection, RpcPeer joined, Hello hello) {
            int id = hello.nodeId();
            RpcPeer dialed = connection.get(DIALED);
            String refusal = joined != null ? "a second Hello"
                    : Math.min(RpcFrames.MAX_VERSION, hello.maxVersion()) < Math.max(RpcFrames.MIN_VERSION, hello.minVersion())
                    ? "node " + id + " speaks protocol versions " + hello.minVersion() + " to " + hello.maxVersion()
                    + ", this node " + RpcFrames.MIN_VERSION + " to " + RpcFrames.MAX_VERSION
                    : dialed != null && id != dialed.nodeId() ? "node " + id + " answered instead of node " + dialed.nodeId()
                    : dialed == null && id == nodeId ? "the other side claims this node's id " + id
                    : dialed == null && closing ? "this node is closing"
                    : !authenticate(connection, hello) ? "node " + id + " was not admitted"
                    : null;
            if (refusal == null) {
                RpcPeer peer = dialed != null ? dialed : peers.computeIfAbsent(id, created -> new RpcPeer(RpcNode.this, created, false));
                refusal = peer.isOutbound() && dialed == null
                        ? "node " + id + " dials in, but this node dials it; only one of two nodes may dial the other"
                        : peer.attach(connection, hello, dialed != null ? () -> {} : () -> hello(connection));
            }
            if (refusal != null) refuse(connection, refusal);
        }

        private boolean authenticate(Connection connection, Hello hello) {
            if (authenticator == null) return true;
            try {
                return authenticator.authenticate(connection, hello.nodeId(), hello.metadata());
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "The RPC authenticator failed on node " + hello.nodeId(), e);
                return false;
            }
        }

        private void hello(Connection connection) {
            RpcFrames.send(connection, new Hello(RpcFrames.MIN_VERSION, RpcFrames.MAX_VERSION, nodeId, incarnation, metadata));
        }

        /** A request whose caller still waits is handed on, and counted until it is answered. */
        private void request(RpcPeer peer, Connection connection, RpcRequest request) {
            if (request.isExpired()) return;
            Set<Integer> waiting = connection.get(UNANSWERED);
            if (waiting == null) connection.set(UNANSWERED, waiting = new HashSet<>());
            if (waiting.add(request.requestId())) unanswered.incrementAndGet();
            dispatch(peer, connection, request);
        }

        private void dispatch(RpcPeer peer, Connection connection, Object msg) {
            try {
                handler.handleMessage(peer, connection, msg);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "The RPC handler failed on " + msg + " from node " + peer.nodeId(), e);
                if (msg instanceof RpcRequest request) RpcResponse.failure(request.requestId(), RPC_HANDLER_ERROR).writeTo(connection);
            }
        }

        /** The connection leaves its peer, and the requests that came on it can no longer be answered. */
        @Override public void onInActive(Connection connection) {
            RpcPeer peer = RpcPeer.of(connection);
            if (peer != null) peer.detach(connection);
            Set<Integer> waiting = connection.get(UNANSWERED);
            if (waiting != null) {
                unanswered.addAndGet(-waiting.size());
                waiting.clear();
            }
        }

        /** Pings when this side has written nothing for a while; closes when the other side has sent nothing. */
        @Override public void onEvent(Connection connection, Object event) {
            if (!(event instanceof IdleStateEvent idle)) return;
            if (idle.state() == IdleState.WRITER_IDLE) {
                if (RpcPeer.of(connection) != null) RpcFrames.send(connection, Ping.INSTANCE);
            } else if (idle.state() == IdleState.READER_IDLE) {
                refuse(connection, "nothing received for " + idleTimeout);
            }
        }

        @Override public void onException(Connection connection, Throwable cause) {
            if (cause instanceof IOException) {
                if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, "RPC connection failed; " + connection + "; " + cause);
            } else {
                LOG.log(Level.WARNING, "Closing RPC connection " + connection, cause);
            }
            connection.close();
        }
    }

    public static final class Builder {
        private Integer nodeId;
        private Metadata metadata = Metadata.empty();
        private SocketAddress listen;
        private RpcHandler handler = NO_HANDLER;
        private RpcListener listener;
        private RpcAuthenticator authenticator;
        private RpcObserver observer;
        private SslContext serverSsl, clientSsl;
        private Bootstrap bootstrap = new Bootstrap();
        private ServerBootstrap serverBootstrap = new ServerBootstrap();
        private Duration timeout = DEFAULT_TIMEOUT, idleTimeout = DEFAULT_IDLE_TIMEOUT;
        private Duration redialMin = NetworkClient.DEFAULT_MIN_RETRY, redialMax = NetworkClient.DEFAULT_MAX_RETRY;
        private int maxFrameLength = DEFAULT_MAX_FRAME_LENGTH;

        private Builder() {}

        /** Required: this node's id, unique in the cluster, which its peers know it by. */
        public Builder nodeId(int id) { nodeId = id; return this; }

        /** What this node tells every peer in the handshake, such as what it is for; empty by default. */
        public Builder metadata(Metadata metadata) { this.metadata = Objects.requireNonNull(metadata, "metadata"); return this; }

        /** Lets other nodes dial this one on {@code port} of all interfaces; a node that only dials need not listen. */
        public Builder listen(int port) { return listen(new InetSocketAddress(port)); }

        /** Any address the server bootstrap's channel type binds, such as Netty's LocalAddress for the local transport. */
        public Builder listen(SocketAddress address) { listen = Objects.requireNonNull(address, "address"); return this; }

        /** Handles what peers send. Without one, requests fail with HANDLER_NOT_FOUND and notifies are dropped. */
        public Builder handler(RpcHandler handler) { this.handler = Objects.requireNonNull(handler, "handler"); return this; }

        /** Hears peers come, go, restart and close; none by default. */
        public Builder listener(RpcListener listener) { this.listener = Objects.requireNonNull(listener, "listener"); return this; }

        /** Admits the nodes on the other side of each connection; all by default. */
        public Builder authenticator(RpcAuthenticator authenticator) {
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
            return this;
        }

        /** Sees every call this node made end; none by default. */
        public Builder observer(RpcObserver observer) { this.observer = Objects.requireNonNull(observer, "observer"); return this; }

        /** TLS for the connections other nodes dial in on; with client authentication it checks their certificates. */
        public Builder serverSsl(SslContext ssl) {
            if (!Objects.requireNonNull(ssl, "ssl").isServer()) throw new IllegalArgumentException("Server SslContext required");
            serverSsl = ssl;
            return this;
        }

        /** TLS for the connections this node dials, with this node's certificate when the other side asks for one. */
        public Builder clientSsl(SslContext ssl) {
            if (!Objects.requireNonNull(ssl, "ssl").isClient()) throw new IllegalArgumentException("Client SslContext required");
            clientSsl = ssl;
            return this;
        }

        /**
         * How this node dials: EventLoops, channel type and options, as Netty's own bootstrap, which must leave the
         * handler to the node. Unset, the node uses EventLoops of its own, shut down on close, and the channel type
         * that matches them; it sets {@link #DEFAULT_WRITE_BUFFER_WATER_MARK} and TCP_NODELAY unless set here.
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

        /** How long a call waits for its answer unless the call says otherwise; 5 seconds by default. */
        public Builder timeout(Duration timeout) {
            if (timeout.toMillis() < 1) throw new IllegalArgumentException("timeout must be at least one millisecond");
            this.timeout = timeout;
            return this;
        }

        /**
         * How long a connection may receive nothing before it is taken as dead and closed; 15 seconds by default.
         * Each side sends a Ping when it has written nothing for a third of it, so every node of the cluster must
         * use the same value.
         */
        public Builder idleTimeout(Duration timeout) {
            if (timeout.toMillis() < 3) throw new IllegalArgumentException("idleTimeout must be at least 3 milliseconds");
            idleTimeout = timeout;
            return this;
        }

        /**
         * The largest frame, its 4-byte length included; 8 MiB by default. Larger messages are refused before they
         * are sent. Every node of the cluster must use the same value.
         */
        public Builder maxFrameLength(int bytes) {
            if (bytes < 64) throw new IllegalArgumentException("maxFrameLength must be at least 64");
            maxFrameLength = bytes;
            return this;
        }

        /** Listens, when asked to, and returns the running node. A bind failure throws NetworkException. */
        public RpcNode start() {
            if (nodeId == null) throw new IllegalStateException("nodeId is required");
            return new RpcNode(this);
        }
    }
}
