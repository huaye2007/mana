package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_NODE_CLOSED;
import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;

import cn.managame.FrameworkErrorCodes;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkHandlers;
import cn.managame.network.netty.NetworkServer;
import cn.managame.network.netty.NetworkTransports;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameTask;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.dispatch.HandleInterceptor;
import cn.managame.rpc.RpcFrame.Failure;
import cn.managame.rpc.RpcFrame.Hello;
import cn.managame.rpc.RpcFrame.Ping;
import cn.managame.rpc.RpcFrame.Request;
import cn.managame.rpc.RpcFrame.Response;
import cn.managame.rpc.RpcLink.Pending;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This process in a cluster, calling and called by its {@link RpcPeer}s, with all business logic running through
 * the runtime. Requests are handled by {@link HandleMethod}s, as player messages are:
 *
 * <pre>{@code
 * @Domain(RANK)
 * class RankService {
 *     @HandleMethod Integer rankOf(RankOf query) { return ranks.of(Context.current().businessId()); }
 * }
 * RpcNode node = RpcNode.builder(runtime)
 *         .nodeId(1).codec(codec).listen(7001)
 *         .peer(2, new InetSocketAddress("10.0.0.2", 7001))
 *         .register(rankService)
 *         .start();
 *
 * // On node 2, in a player's request handler:
 * node.peer(1).call(new RankOf(), (Context c, Integer rank) -> request.reply(rank), (c, errorCode) -> request.fail(errorCode));
 * }</pre>
 *
 * <p>A peer is any other node this one connects to or that connects to this one, and may have several
 * connections: those this node opens to the peers it is configured with, kept up by reconnecting, and those
 * peers open to it. Each connection starts with both sides sending a Hello naming their node, after which it
 * carries calls both ways.
 *
 * <p>Either side of a connection that has written nothing for a third of the idle timeout sends a Ping, so a live
 * connection is never silent for long. One that has received nothing for the whole idle timeout, such as one to a
 * machine that went down without closing it, is taken as dead and closed: its calls fail with RPC_UNAVAILABLE at
 * once, and a connection this node opened is reconnected.
 *
 * <p>A request runs at the receiver in its handler's domain, on the routing key that domain's resolver finds,
 * acting for the caller's business identity with the caller's metadata; the handler's {@code HandleContext.source()}
 * is an {@link RpcSource}. The result, or the failure, comes back as a task on the calling context's route, so a
 * call made while answering a player can answer the player from its callback. Calls never throw for the network:
 * a peer that is not connected, a timeout, a dropped connection or a closed node reach the failure callback as
 * RPC_UNAVAILABLE, RPC_TIMEOUT or RPC_NODE_CLOSED, and a request the peer failed carries the peer's code, such as
 * HANDLER_NOT_FOUND, RUNTIME_EXECUTION_ERROR, or RPC_HANDLER_ERROR for a result its codec could not encode. A
 * notice expects no answer.
 */
public final class RpcNode implements AutoCloseable {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_FRAME_LENGTH = 8 * 1024 * 1024;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(15);
    static final GameContext NO_CALLER = GameContext.of(0, 0);
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    private static final AttributeKey<RpcLink> LINK = AttributeKey.valueOf("cn.managame.rpc.link");
    private final int nodeId;
    private final GameRuntime runtime;
    private final RpcCodec codec;
    private final Dispatcher dispatcher;
    private final Map<Integer, RpcPeer> peers = new ConcurrentHashMap<>();
    private final long timeoutNanos;
    private final Duration idleTimeout;
    private final RpcFrameCodec frames = new RpcFrameCodec();
    private final AtomicInteger requestIds = new AtomicInteger();
    private final HashedWheelTimer timeouts;
    private final EventLoopGroup clientGroup;
    private final List<NetworkClient> clients = new CopyOnWriteArrayList<>();
    private volatile NetworkServer listener;
    private volatile boolean closed;

    private RpcNode(Builder b) {
        nodeId = b.nodeId;
        runtime = b.runtime;
        codec = b.codec;
        dispatcher = b.handlers.build();
        timeoutNanos = b.timeout.toNanos();
        idleTimeout = b.idleTimeout;
        timeouts = new HashedWheelTimer(Thread.ofPlatform().name("rpc-timeout").daemon().factory(),
                10, TimeUnit.MILLISECONDS, 512, false, -1);
        clientGroup = NetworkTransports.newEventLoopGroup(0, "rpc-client");
    }

    /** Listens and connects once the node is fully built; on failure releases what it started. */
    private RpcNode open(Builder b) {
        try {
            if (b.listen != null) listener = NetworkServer.start(b.listen, pipeline(b.maxFrameLength).handler(new Links(null)).build());
            b.peers.forEach((id, remote) -> {
                Bootstrap bootstrap = new Bootstrap().group(clientGroup)
                        .handler(pipeline(b.maxFrameLength).handler(new Links(peer(id))).build());
                for (int i = 0; i < remote.connections(); i++) clients.add(NetworkClient.connect(bootstrap, remote.address()));
            });
            return this;
        } catch (RuntimeException e) {
            try { close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /** Idle detection first, so that any bytes received count, then the framing and the frames. */
    private ConnectionInitializer.Builder pipeline(int maxFrameLength) {
        long idle = idleTimeout.toNanos();
        return ConnectionInitializer.tcp()
                .addLast("rpc-idle", () -> new IdleStateHandler(idle, idle / 3, 0, TimeUnit.NANOSECONDS))
                .addLast(NetworkHandlers.lengthFieldFraming(maxFrameLength))
                .addLast("rpc-frames", frames);
    }

    public static Builder builder(GameRuntime runtime) { return new Builder(runtime); }

    public int nodeId() { return nodeId; }

    /** The listening port, or -1 when the node does not listen. */
    public int port() { return listener == null ? -1 : listener.port(); }

    /**
     * The peer with this node id, whether or not it is connected now; always the same instance for one id. This
     * node's own id throws IllegalArgumentException.
     */
    public RpcPeer peer(int nodeId) {
        if (nodeId == this.nodeId) throw new IllegalArgumentException("Node " + nodeId + " is this node");
        return peers.computeIfAbsent(nodeId, id -> new RpcPeer(this, id));
    }

    void call(RpcPeer peer, Context context, Object request, RpcResult<Object> onResult, RpcFailure onFailure) {
        Pending pending = new Pending(context, onResult, onFailure);
        RpcLink link = closed ? null : peer.link(context);
        if (link == null) {
            fail(pending, closed ? RPC_NODE_CLOSED : RPC_UNAVAILABLE);
            return;
        }
        ByteBuf body = encode(request);
        int requestId = nextRequestId();
        link.pending.put(requestId, pending);
        try {
            pending.timeout = timeouts.newTimeout(expired -> {
                if (link.pending.remove(requestId, pending)) fail(pending, RPC_TIMEOUT);
            }, timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (IllegalStateException stopped) { // the node closed since the check above
            body.release();
            if (link.pending.remove(requestId, pending)) fail(pending, RPC_NODE_CLOSED);
            return;
        }
        if (link.connection.write(new Request(requestId, GameContext.copyOf(context), body)) != WriteStatus.ACCEPTED) {
            body.release();
            if (link.pending.remove(requestId, pending)) {
                pending.cancelTimeout();
                fail(pending, RPC_UNAVAILABLE);
            }
        }
    }

    boolean notice(RpcPeer peer, Context context, Object message) {
        RpcLink link = closed ? null : peer.link(context);
        if (link == null) return false;
        ByteBuf body = encode(message);
        if (link.connection.write(new Request(0, GameContext.copyOf(context), body)) == WriteStatus.ACCEPTED) return true;
        body.release();
        return false;
    }

    private ByteBuf encode(Object message) {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer();
        try {
            codec.encode(message, body);
            return body;
        } catch (RuntimeException e) {
            body.release();
            throw e;
        } catch (Exception e) {
            body.release();
            throw new IllegalArgumentException("Cannot encode " + message.getClass().getName(), e);
        }
    }

    private int nextRequestId() {
        int id = requestIds.incrementAndGet();
        return id != 0 ? id : requestIds.incrementAndGet(); // zero marks a notice
    }

    private void fail(Pending pending, int errorCode) {
        RpcFailure onFailure = pending.onFailure;
        callback(pending.context, onFailure != null ? context -> onFailure.onFailure(context, errorCode)
                : context -> LOG.log(Level.WARNING, "RPC call failed: " + FrameworkErrorCodes.describe(errorCode)));
    }

    private void failAll(RpcLink link, int errorCode) {
        for (Integer requestId : link.pending.keySet()) {
            Pending call = link.pending.remove(requestId);
            if (call == null) continue;
            call.cancelTimeout();
            fail(call, errorCode);
        }
    }

    private void callback(Context context, GameTask<Context> task) {
        try {
            runtime.execute(context, task);
        } catch (RuntimeException refused) {
            LOG.log(Level.WARNING, "Dropped an RPC callback the runtime refused", refused);
        }
    }

    /** A frame from a peer: its Hello first, then requests and answers either way; Pings at any time. */
    private void receive(RpcLink link, RpcFrame frame) {
        if (link.peer == null && !(frame instanceof Hello || frame instanceof Ping)) {
            release(frame);
            LOG.log(Level.WARNING, "Closing RPC connection " + link + ": a frame came before the Hello");
            link.connection.close();
            return;
        }
        switch (frame) {
            case Hello hello -> hello(link, hello.nodeId());
            case Request request -> dispatch(link, request);
            case Response response -> {
                Pending pending = link.pending.remove(response.requestId());
                if (pending == null) { // late after a timeout
                    response.body().release();
                    return;
                }
                pending.cancelTimeout();
                Object result;
                try {
                    result = codec.decode(response.body());
                } catch (Throwable e) {
                    LOG.log(Level.ERROR, "Cannot decode an RPC result from " + link.peer, e);
                    fail(pending, RPC_PROTOCOL_ERROR);
                    return;
                } finally {
                    response.body().release();
                }
                callback(pending.context, context -> pending.onResult.onResult(context, result));
            }
            case Failure failure -> {
                Pending pending = link.pending.remove(failure.requestId());
                if (pending == null) return;
                pending.cancelTimeout();
                fail(pending, failure.errorCode());
            }
            case Ping ping -> {} // receiving it was the point
        }
    }

    /**
     * The peer named itself: the connection joins it. A connection this node opened must reach the peer it was
     * opened to; one a peer opened is answered with this node's Hello first.
     */
    private void hello(RpcLink link, int peerId) {
        RpcPeer expected = link.expected;
        if (link.peer != null || peerId == nodeId || (expected != null && expected.nodeId() != peerId)) {
            LOG.log(Level.WARNING, "Closing RPC connection " + link + ": unexpected Hello from node " + peerId);
            link.connection.close();
            return;
        }
        if (expected == null) link.connection.write(new Hello(nodeId));
        RpcPeer peer = expected != null ? expected : peer(peerId);
        link.peer = peer;
        peer.add(link);
    }

    private void dispatch(RpcLink link, Request request) {
        int requestId = request.requestId();
        Object message;
        try {
            message = codec.decode(request.body());
        } catch (Throwable e) {
            LOG.log(Level.ERROR, "Cannot decode an RPC request from " + link.peer, e);
            if (requestId != 0) link.connection.write(new Failure(requestId, RPC_PROTOCOL_ERROR));
            return;
        } finally {
            request.body().release();
        }
        GameContext caller = request.caller();
        RpcSource source = new RpcSource(link.peer, caller.domain(), caller.routingKey(), caller.businessType(),
                caller.businessId(), caller.metadata());
        if (requestId == 0) {
            dispatcher.dispatch(source, message);
            return;
        }
        dispatcher.dispatch(source, message, result -> reply(link.connection, requestId, result),
                errorCode -> link.connection.write(new Failure(requestId, errorCode)));
    }

    /** A result the codec cannot encode fails the request with RPC_HANDLER_ERROR, and is logged. */
    private void reply(Connection connection, int requestId, Object result) {
        ByteBuf body;
        try {
            body = encode(result);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Cannot encode the RPC result " + result.getClass().getName(), e);
            connection.write(new Failure(requestId, RPC_HANDLER_ERROR));
            return;
        }
        if (connection.write(new Response(requestId, body)) != WriteStatus.ACCEPTED) body.release();
    }

    private static void release(RpcFrame frame) {
        switch (frame) {
            case Request request -> request.body().release();
            case Response response -> response.body().release();
            default -> {}
        }
    }

    /**
     * Fails every pending call with RPC_NODE_CLOSED, then stops listening and disconnects from the peers. Requests
     * already handed to the runtime still run, but their answers may no longer reach the callers.
     */
    @Override public void close() {
        closed = true;
        for (RpcPeer peer : peers.values())
            for (RpcLink link : peer.links()) failAll(link, RPC_NODE_CLOSED);
        try {
            if (listener != null) listener.close();
        } finally {
            for (NetworkClient client : clients) client.close();
            clientGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
            timeouts.stop();
        }
    }

    @Override public String toString() { return "RpcNode[" + nodeId + "]"; }

    /** Every connection: the ones this node opens to {@code expected}, or, with null, the ones peers open. */
    private final class Links implements ConnectionHandler {
        private final RpcPeer expected;

        Links(RpcPeer expected) { this.expected = expected; }

        @Override public void onActive(Connection connection) {
            connection.set(LINK, new RpcLink(connection, expected));
            if (expected != null) connection.write(new Hello(nodeId));
        }

        @Override public void onRead(Connection connection, Object message) { receive(connection.get(LINK), (RpcFrame) message); }

        @Override public void onInActive(Connection connection) {
            RpcLink link = connection.get(LINK);
            RpcPeer peer = link.peer;
            if (peer != null) peer.remove(link);
            failAll(link, closed ? RPC_NODE_CLOSED : RPC_UNAVAILABLE);
        }

        /** Pings when this side has written nothing for a while; closes when the other side has sent nothing. */
        @Override public void onEvent(Connection connection, Object event) {
            if (!(event instanceof IdleStateEvent idle)) return;
            if (idle.state() == IdleState.WRITER_IDLE) {
                connection.write(Ping.INSTANCE);
            } else if (idle.state() == IdleState.READER_IDLE) {
                LOG.log(Level.WARNING, "Closing RPC connection " + connection + ": nothing received for " + idleTimeout);
                connection.close();
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

    private record Remote(SocketAddress address, int connections) {}

    public static final class Builder {
        private final GameRuntime runtime;
        private final Dispatcher.Builder handlers;
        private Integer nodeId;
        private RpcCodec codec;
        private SocketAddress listen;
        private final Map<Integer, Remote> peers = new LinkedHashMap<>();
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
        private int maxFrameLength = DEFAULT_MAX_FRAME_LENGTH;

        private Builder(GameRuntime runtime) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            handlers = Dispatcher.builder(runtime);
        }

        /** Required: this node's id, which its peers know it by. */
        public Builder nodeId(int id) { nodeId = id; return this; }

        /** Required: how messages are encoded; every node of the cluster must use the same format. */
        public Builder codec(RpcCodec codec) { this.codec = Objects.requireNonNull(codec, "codec"); return this; }

        /** Accepts peers' connections on {@code port} of all interfaces; a node that only calls need not listen. */
        public Builder listen(int port) { return listen(new InetSocketAddress(port)); }

        public Builder listen(SocketAddress address) { listen = Objects.requireNonNull(address, "address"); return this; }

        /** A node this one connects to, with one connection. */
        public Builder peer(int id, SocketAddress address) { return peer(id, address, 1); }

        /** A node this one opens {@code connections} connections to, each reconnected when it drops. */
        public Builder peer(int id, SocketAddress address, int connections) {
            Objects.requireNonNull(address, "address");
            if (connections < 1) throw new IllegalArgumentException("connections must be positive");
            if (peers.putIfAbsent(id, new Remote(address, connections)) != null)
                throw new IllegalArgumentException("Duplicate peer " + id);
            return this;
        }

        /**
         * Registers every {@link HandleMethod} of each object to handle the requests and notices peers send, as
         * {@link Dispatcher.Builder#register} does.
         */
        public Builder register(Object... targets) { handlers.register(targets); return this; }

        /**
         * Interceptors for every request and notice handler, as {@link Dispatcher.Builder#intercept} adds them; a
         * handler's {@code HandleContext.source()} is the request's {@link RpcSource}.
         */
        public Builder intercept(HandleInterceptor... interceptors) { handlers.intercept(interceptors); return this; }

        /** How long a call waits for its answer; 5 seconds by default. */
        public Builder timeout(Duration timeout) {
            if (timeout.toMillis() < 1) throw new IllegalArgumentException("timeout must be at least one millisecond");
            this.timeout = timeout;
            return this;
        }

        /**
         * How long a connection may receive nothing before it is taken as dead and closed; 15 seconds by default.
         * Each side sends a Ping when it has written nothing for a third of it. Every node of the cluster must use
         * the same value.
         */
        public Builder idleTimeout(Duration timeout) {
            if (timeout.toMillis() < 3) throw new IllegalArgumentException("idleTimeout must be at least 3 milliseconds");
            idleTimeout = timeout;
            return this;
        }

        /** Largest frame; 8 MiB by default. */
        public Builder maxFrameLength(int bytes) {
            if (bytes <= 0) throw new IllegalArgumentException("maxFrameLength must be positive");
            maxFrameLength = bytes;
            return this;
        }

        /** Listens, connects to the peers and returns the running node. A bind failure throws NetworkException. */
        public RpcNode start() {
            if (nodeId == null) throw new IllegalStateException("nodeId is required");
            if (codec == null) throw new IllegalStateException("codec is required");
            if (peers.containsKey(nodeId)) throw new IllegalStateException("A node cannot be its own peer");
            return new RpcNode(this).open(this);
        }
    }
}
