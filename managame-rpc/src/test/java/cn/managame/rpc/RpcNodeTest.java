package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ResourceLeakDetector;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Node 1, a world server, listens; node 2, a game server that does not listen, dials it. */
class RpcNodeTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);
    private static final MetadataKey<Integer> SERVICE = MetadataKey.intKey(2);
    private static final MetadataKey<String> TOKEN = MetadataKey.stringKey(3);
    private static final int ECHO = 1, FIELDS = 2, LATER = 3, KEEP = 4, BOOM = 5, SILENT = 6, TWICE = 7, BIG = 8,
            HOLD = 9, CLOSE = 10, DROP = 11, NOTE = 20, ORDER = 21;

    /** What handlers saw, what listeners heard, and what callbacks got. */
    private final BlockingQueue<String> seen = new LinkedBlockingQueue<>(), events = new LinkedBlockingQueue<>(),
            answers = new LinkedBlockingQueue<>();
    /** Requests a HOLD handler keeps, to be answered by the test. */
    private final BlockingQueue<Held> held = new LinkedBlockingQueue<>();
    private final Map<Long, List<Integer>> orders = new ConcurrentHashMap<>();
    private final List<RpcNode> nodes = new ArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private Duration timeout = RpcNode.DEFAULT_TIMEOUT, heartbeat = Duration.ofSeconds(5);
    private RpcNode world, game;

    record Held(Connection connection, int requestId, String text) {}

    @BeforeAll static void detectLeaks() { ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID); }

    @AfterEach void closeNodes() throws Exception {
        nodes.forEach(RpcNode::close);
        for (AutoCloseable closeable : closeables) closeable.close();
    }

    /** A handler as an application writes one: it decides by the message and its command what to do. */
    private RpcHandler handler(String name) {
        return (rpcPeer, connection, msg) -> {
            switch (msg) {
                case RpcRequest request -> answer(name, rpcPeer, connection, request);
                case RpcNotify notify when notify.command() == ORDER ->
                        orders.computeIfAbsent(notify.routeKey(), key -> new CopyOnWriteArrayList<>()).add(notify.body().getInt(notify.body().readerIndex()));
                case RpcNotify notify -> seen.add(name + " notified " + text(notify.body()) + " from " + rpcPeer.nodeId());
                default -> fail("Unexpected " + msg);
            }
        };
    }

    private void answer(String name, RpcPeer rpcPeer, Connection connection, RpcRequest request) {
        int id = request.requestId();
        String text = text(request.body()); // decoded here: the request is released when the handler returns
        switch (request.command()) {
            case ECHO -> RpcResponse.of(id, request.metadata(), text(name + " got " + text + " from " + rpcPeer.nodeId())).writeTo(connection);
            case FIELDS -> RpcResponse.of(id, text(request.routeKey() + " " + request.businessType() + " " + request.businessId()
                    + " " + request.metadata().get(TRACE) + " " + (id != 0) + " " + (request.deadline() != 0))).writeTo(connection);
            case LATER -> Thread.ofVirtual().start(() -> {
                sleep(200);
                RpcResponse.of(id, text(name + " later " + text)).writeTo(connection);
            });
            case KEEP -> { // keeps the request past the handler: retains it, and releases it when done
                request.retain();
                Thread.ofVirtual().start(() -> {
                    try {
                        RpcResponse.of(id, text(name + " kept " + text(request.body()))).writeTo(connection);
                    } finally {
                        request.release();
                    }
                });
            }
            case BOOM -> throw new IllegalStateException("boom");
            case SILENT -> {}
            case TWICE -> {
                RpcResponse.of(id, text("first")).writeTo(connection);
                RpcResponse.of(id, text("second")).writeTo(connection);
            }
            case BIG -> RpcResponse.of(id, text("y".repeat(2000))).writeTo(connection);
            case HOLD -> held.add(new Held(connection, id, text));
            case CLOSE -> {
                RpcResponse.of(id, text(name + " closing")).writeTo(connection);
                connection.close();
            }
            case DROP -> { // the connection drops before the answer is ready, which then goes on another connection
                connection.close();
                Thread.ofVirtual().start(() -> {
                    await(() -> !connection.isActive());
                    sleep(100);
                    seen.add(name + " answered after the drop: " + RpcResponse.of(id, text(name + " still got " + text)).writeTo(connection));
                });
            }
            default -> RpcResponse.failure(id, 999).writeTo(connection);
        }
    }

    private RpcListener listener(String name) {
        return new RpcListener() {
            @Override public void onConnected(RpcPeer peer, boolean restarted) {
                events.add(name + " connected " + peer.nodeId() + (restarted ? " restarted" : "") + " with " + peer.connectionCount());
            }

            @Override public void onDisconnected(RpcPeer peer) { events.add(name + " disconnected " + peer.nodeId()); }
        };
    }

    /** Records "ok <body>" or "fail <code>", and "on the caller" when it ran on the thread that made the call. */
    private RpcCallback recorder() {
        Thread caller = Thread.currentThread();
        return (request, response) -> answers.add((Thread.currentThread() == caller ? "on the caller " : "")
                + (response.isSuccess() ? "ok " + text(response.body()) : "fail " + response.code()));
    }

    private RpcNode.Builder node(int id, String name) {
        return RpcNode.builder().nodeId(id).handler(handler(name)).listener(listener(name)).timeout(timeout).heartbeatInterval(heartbeat);
    }

    private RpcNode start(RpcNode.Builder builder) {
        RpcNode node = builder.start();
        nodes.add(node);
        return node;
    }

    private static InetSocketAddress at(RpcNode node) { return new InetSocketAddress("127.0.0.1", node.port()); }

    /** World listens, game dials it with {@code connections} connections; both see each other connected. */
    private RpcPeer connect(int connections) {
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game").redialDelay(Duration.ofSeconds(10), Duration.ofSeconds(10)));
        RpcPeer peer = game.createPeer(1, at(world), connections);
        await(() -> peer.connectionCount() == connections && world.peer(2) != null && world.peer(2).connectionCount() == connections);
        return peer;
    }

    @Test void aCallIsAnsweredAndItsCallbackGetsTheRequestBack() throws Exception {
        RpcPeer peer = connect(1);
        Metadata trace = Metadata.builder().put(TRACE, "t-1").build();
        peer.call(RpcRequest.of(ECHO, 7, (byte) 3, 42, trace, text("hi")), recorder());
        assertEquals("ok world got hi from 2", next(answers));
        peer.call(RpcRequest.of(FIELDS, 7, (byte) 3, 42, trace, text("")), recorder());
        assertEquals("ok 7 3 42 t-1 true true", next(answers), "every field reached the callee, with an id and a deadline");

        BlockingQueue<String> back = new LinkedBlockingQueue<>();
        peer.call(RpcRequest.of(ECHO, 9, (byte) 5, 77, trace, text("x")), (request, response) -> back.add(request.command() + " "
                + request.routeKey() + " " + request.businessType() + " " + request.businessId() + " " + request.metadata().get(TRACE)
                + " " + (request.requestId() != 0) + " " + (request.deadline() != 0) + " " + request.body().readableBytes()
                + " " + response.metadata().get(TRACE)));
        assertEquals(ECHO + " 9 5 77 t-1 true true 0 t-1", next(back), "the request as sent, without its body, and the answer's metadata");
    }

    @Test void answersMayComeLaterFromAnotherThread() throws Exception {
        RpcPeer peer = connect(1);
        peer.call(RpcRequest.of(LATER, text("a")), recorder());
        peer.call(RpcRequest.of(KEEP, text("b")), recorder());
        assertEquals(List.of("ok world kept b", "ok world later a"), List.of(next(answers), next(answers)));
    }

    @Test void theDialedNodeCallsAndNotifiesTheNodeThatDialedIt() throws Exception {
        connect(1);
        RpcPeer gamePeer = world.peer(2);
        assertFalse(gamePeer.isOutbound());
        gamePeer.call(RpcRequest.of(ECHO, text("hello")), recorder());
        assertEquals("ok game got hello from 1", next(answers));
        assertEquals(WriteStatus.ACCEPTED, gamePeer.notify(RpcNotify.of(NOTE, text("mail"))));
        assertEquals("game notified mail from 1", next(seen));
        assertEquals(WriteStatus.ACCEPTED, game.peer(1).notify(RpcNotify.of(NOTE, text("up"))));
        assertEquals("world notified up from 2", next(seen));
    }

    @Test void aBroadcastSharesOneBody() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        RpcNode first = start(node(2, "first")), second = start(node(3, "second"));
        first.createPeer(1, at(world));
        second.createPeer(1, at(world));
        await(() -> world.peers().size() == 2 && world.peers().stream().allMatch(RpcPeer::isConnected));
        ByteBuf body = text("news");
        assertEquals(2, world.broadcast(world.peers(), RpcNotify.of(NOTE, body)));
        assertEquals(List.of("first notified news from 1", "second notified news from 1"), sorted(next(seen), next(seen)));
        await(() -> body.refCnt() == 0);
    }

    @Test void failuresReachTheCallbackAsCodes() throws Exception {
        timeout = Duration.ofMillis(300);
        RpcPeer peer = connect(1);
        peer.call(RpcRequest.of(BOOM, text("")), recorder());
        assertEquals("fail " + RPC_HANDLER_ERROR, next(answers));
        peer.call(RpcRequest.of(SILENT, text("")), recorder());
        assertEquals("fail " + RPC_TIMEOUT, next(answers), "the node's timeout");
        long start = System.nanoTime();
        peer.call(RpcRequest.of(SILENT, text("")), Duration.ofMillis(50), recorder());
        assertEquals("fail " + RPC_TIMEOUT, next(answers));
        assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(250), "the call's own timeout");
        peer.call(RpcRequest.of(TWICE, text("")), recorder());
        assertEquals("ok first", next(answers));
        peer.call(RpcRequest.of(ECHO, text("after")), recorder());
        assertEquals("ok world got after from 2", next(answers), "the second answer was dropped");
        assertEquals(0, peer.pendingCount());

        RpcNode plain = start(RpcNode.builder().nodeId(3).listen(LOOPBACK));
        RpcPeer handlerless = game.createPeer(3, at(plain));
        await(handlerless::isConnected);
        handlerless.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + HANDLER_NOT_FOUND, next(answers));
    }

    @Test void aCallThatCannotGoOutFailsOffTheCallingThread() throws Exception {
        game = start(node(2, "game"));
        RpcPeer nobody = game.createPeer(1, new InetSocketAddress("127.0.0.1", freePort()));
        ByteBuf body = text("lost");
        nobody.call(RpcRequest.of(ECHO, body), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "not on the caller");
        assertEquals(0, body.refCnt());
        BlockingQueue<String> threads = new LinkedBlockingQueue<>();
        nobody.call(RpcRequest.of(ECHO, text("")), (request, response) -> threads.add(Thread.currentThread().getName()));
        assertTrue(next(threads).startsWith("rpc-io"), "on one of the node's EventLoops");
        ByteBuf note = text("lost");
        assertEquals(WriteStatus.INACTIVE, nobody.notify(RpcNotify.of(NOTE, note)));
        assertEquals(0, note.refCnt());
    }

    @Test void messagesLargerThanTheLargestFrameFailAloneAndTheConnectionSurvives() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK).maxFrameLength(1000));
        game = start(node(2, "game").maxFrameLength(1000));
        RpcPeer peer = game.createPeer(1, at(world));
        await(() -> peer.isConnected() && world.peer(2) != null && world.peer(2).isConnected());

        ByteBuf huge = text("x".repeat(2000));
        assertThrows(IllegalArgumentException.class, () -> peer.call(RpcRequest.of(ECHO, huge), recorder()));
        assertEquals(0, huge.refCnt());
        ByteBuf hugeNote = text("x".repeat(2000));
        assertThrows(IllegalArgumentException.class, () -> peer.notify(RpcNotify.of(NOTE, hugeNote)));
        assertEquals(0, hugeNote.refCnt());

        world.peer(2).call(RpcRequest.of(BIG, text("")), recorder());
        assertEquals("fail " + RPC_PROTOCOL_ERROR, next(answers), "the game's answer is larger than its largest frame");
        peer.call(RpcRequest.of(ECHO, text("small")), recorder());
        assertEquals("ok world got small from 2", next(answers));
        assertEquals(1, peer.connectionCount());
    }

    @Test void messagesWithOneKeyKeepTheirOrderOverSeveralConnections() throws Exception {
        RpcPeer peer = connect(4);
        int perKey = 2000;
        for (int i = 0; i < perKey; i++)
            for (long key = 0; key < 8; key++)
                while (peer.notify(RpcNotify.of(ORDER, key, Unpooled.buffer(4).writeInt(i))) != WriteStatus.ACCEPTED) Thread.onSpinWait();
        await(() -> orders.size() == 8 && orders.values().stream().allMatch(list -> list.size() == perKey));
        for (List<Integer> order : orders.values())
            for (int i = 0; i < perKey; i++) assertEquals(i, order.get(i));
    }

    @Test void aKeyWhoseConnectionIsDownTakesAnotherOne() throws Exception {
        RpcPeer peer = connect(2);
        peer.call(RpcRequest.of(CLOSE, 1, text("")), recorder()); // the world closes slot 1's connection
        assertEquals("ok world closing", next(answers));
        await(() -> peer.connectionCount() == 1);
        assertTrue(events.isEmpty() || !events.contains("game disconnected 1"), "one connection lost: still connected");
        for (long key = 0; key < 4; key++) peer.call(RpcRequest.of(ECHO, key, text("k" + key)), recorder());
        for (int i = 0; i < 4; i++) assertTrue(next(answers).startsWith("ok world got k"));
        assertEquals(WriteStatus.ACCEPTED, peer.notify(RpcNotify.of(NOTE, 1, text("still"))));
        assertEquals("world notified still from 2", next(seen));
    }

    @Test void anAnswerOutlivesTheConnectionItsRequestCameOn() throws Exception {
        RpcPeer peer = connect(2);
        peer.call(RpcRequest.of(DROP, 1, text("q")), Duration.ofSeconds(5), recorder());
        assertEquals("world answered after the drop: ACCEPTED", next(seen));
        assertEquals("ok world still got q", next(answers), "the answer came on the other connection");
        assertEquals(1, peer.connectionCount());
        assertEquals(0, peer.pendingCount());
    }

    /** One connection, cut while a request waits: the call fails at once, and the answer written meanwhile is lost. */
    @Test void callsFailAtOnceWhenThePeersLastConnectionCloses() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        Proxy proxy = new Proxy(world.port());
        closeables.add(proxy);
        game = start(node(2, "game").redialDelay(Duration.ofMillis(300), Duration.ofMillis(300)));
        RpcPeer peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", proxy.port()));
        await(() -> peer.isConnected() && world.peer(2) != null && world.peer(2).isConnected());
        peer.call(RpcRequest.of(HOLD, text("")), Duration.ofSeconds(10), recorder());
        Held request = next(held);
        long start = System.nanoTime();
        proxy.cut();
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "long before the call's timeout");
        assertEquals(0, peer.pendingCount());
        await(() -> !world.peer(2).isConnected());
        ByteBuf lost = text("lost");
        assertEquals(WriteStatus.INACTIVE, RpcResponse.of(request.requestId(), lost).writeTo(request.connection()));
        assertEquals(0, lost.refCnt());
    }

    @Test void withNoConnectionUpCallsFailAndTheirCallbacksLeaveTheCaller() throws Exception {
        RpcPeer peer = connect(2);
        world.close();
        await(() -> !peer.isConnected());
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
        assertEquals(WriteStatus.INACTIVE, peer.notify(RpcNotify.of(NOTE, text(""))));
    }

    @Test void thePeerIsConnectedOnceEveryConnectionIsAndDisconnectedOnceNoneIs() throws Exception {
        RpcPeer peer = connect(3);
        assertEquals(List.of("game connected 1 with 3", "world connected 2 with 3"), sorted(next(events), next(events)));
        peer.call(RpcRequest.of(CLOSE, 0, text("")), recorder());
        next(answers);
        await(() -> peer.connectionCount() == 2);
        sleep(100);
        assertTrue(events.isEmpty(), "losing one connection of three is no event: " + events);
        world.close();
        assertEquals(List.of("game disconnected 1", "world disconnected 2"), sorted(next(events), next(events)));
    }

    @Test void theApplicationGroupsPeersByWhatTheySayInTheirHandshake() throws Exception {
        Map<Integer, List<Integer>> byService = new ConcurrentHashMap<>();
        world = start(node(1, "world").listen(LOOPBACK).listener(new RpcListener() {
            @Override public void onConnected(RpcPeer peer, boolean restarted) {
                byService.computeIfAbsent(peer.metadata().get(SERVICE), service -> new CopyOnWriteArrayList<>()).add(peer.nodeId());
            }
        }));
        for (int id = 2; id <= 4; id++)
            start(node(id, "node" + id).metadata(Metadata.builder().put(SERVICE, id == 4 ? 20 : 10).build())).createPeer(1, at(world));
        await(() -> byService.values().stream().mapToInt(List::size).sum() == 3);
        assertEquals(List.of(2, 3), byService.get(10).stream().sorted().toList());
        assertEquals(List.of(4), byService.get(20));
    }

    @Test void aRestartIsToldFromAReconnect() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        Proxy proxy = new Proxy(world.port());
        closeables.add(proxy);
        game = start(node(2, "game").redialDelay(Duration.ofMillis(50), Duration.ofMillis(50)));
        RpcPeer peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", proxy.port()));
        assertEquals(List.of("game connected 1 with 1", "world connected 2 with 1"), sorted(next(events), next(events)));
        long first = peer.incarnation();

        proxy.cut(); // a reconnect: the same instance
        assertEquals(List.of("game disconnected 1", "world disconnected 2"), sorted(next(events), next(events)));
        assertEquals(List.of("game connected 1 with 1", "world connected 2 with 1"), sorted(next(events), next(events)));
        assertEquals(first, peer.incarnation());

        RpcNode restarted = start(node(1, "world").listen(LOOPBACK));
        proxy.target(restarted.port());
        proxy.cut(); // a restart: a new instance
        await(() -> peer.isConnected() && peer.incarnation() == restarted.incarnation());
        assertTrue(drain(events).contains("game connected 1 restarted with 1"));
        peer.call(RpcRequest.of(ECHO, text("again")), recorder());
        assertEquals("ok world got again from 2", next(answers));
    }

    @Test void aNewerInstanceReplacesTheConnectedOneAndAnOlderOneIsRefused() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        RpcNode older = start(node(2, "older").redialDelay(Duration.ofMillis(50), Duration.ofMillis(50)));
        RpcPeer olderPeer = older.createPeer(1, at(world));
        await(() -> world.peer(2) != null && world.peer(2).incarnation() == older.incarnation() && world.peer(2).isConnected());
        world.peer(2).call(RpcRequest.of(HOLD, text("")), Duration.ofSeconds(10), recorder());
        next(held);
        sleep(2);
        RpcNode newer = start(node(2, "newer"));
        assertTrue(newer.incarnation() > older.incarnation());
        RpcPeer newerPeer = newer.createPeer(1, at(world));
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "the older instance can no longer answer it");
        await(() -> world.peer(2).incarnation() == newer.incarnation() && newerPeer.isConnected());
        sleep(300); // the older one keeps dialing and is refused
        assertEquals(newer.incarnation(), world.peer(2).incarnation());
        assertFalse(olderPeer.isConnected());
        world.peer(2).call(RpcRequest.of(ECHO, text("who")), recorder());
        assertEquals("ok newer got who from 1", next(answers));
    }

    @Test void twoNodesMustNotDialEachOther() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game").listen(LOOPBACK));
        RpcPeer peer = game.createPeer(1, at(world));
        await(() -> world.peer(2) != null && world.peer(2).isConnected());
        assertThrows(IllegalStateException.class, () -> world.createPeer(2, at(game)));
        assertThrows(IllegalStateException.class, () -> game.createPeer(1, at(world)));
        assertThrows(IllegalArgumentException.class, () -> game.createPeer(2, at(world)));
        assertThrows(IllegalArgumentException.class, () -> game.createPeer(3, at(world), 0));
        assertTrue(peer.isConnected());
    }

    @Test void aNodeAnsweringUnderAnotherIdIsRefused() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game"));
        RpcPeer wrong = game.createPeer(5, at(world));
        sleep(300);
        assertFalse(wrong.isConnected());
        await(() -> world.peer(2) == null || !world.peer(2).isConnected()); // the world let it join; the dialing side refused
    }

    @Test void theAuthenticatorDecidesInBothDirections() throws Exception {
        RpcAuthenticator tokens = (connection, nodeId, metadata) -> "secret".equals(metadata.get(TOKEN));
        Metadata secret = Metadata.builder().put(TOKEN, "secret").build();
        world = start(node(1, "world").listen(LOOPBACK).authenticator(tokens).metadata(secret));
        RpcNode stranger = start(node(3, "stranger"));
        RpcPeer refused = stranger.createPeer(1, at(world));
        game = start(node(2, "game").authenticator(tokens).metadata(secret));
        RpcPeer admitted = game.createPeer(1, at(world));
        await(admitted::isConnected);
        sleep(200);
        assertFalse(refused.isConnected());
        assertNull(world.peer(3), "a node refused while dialing in leaves no peer");

        RpcNode picky = start(node(4, "picky").authenticator((connection, nodeId, metadata) -> false));
        RpcPeer refusing = picky.createPeer(1, at(world));
        sleep(200);
        assertFalse(refusing.isConnected(), "the dialing side refuses the dialed node");
    }

    @Test void handshakesAreChecked() throws Exception {
        heartbeat = Duration.ofMillis(100);
        world = start(node(1, "world").listen(LOOPBACK));
        try (Socket socket = socket(world)) {
            writeHandshake(socket, 2, 3, 0, 1);
            assertEquals(-1, socket.getInputStream().read(), "another protocol version");
        }
        try (Socket socket = socket(world)) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(1);
            out.writeByte(1); // a heartbeat
            out.flush();
            assertEquals(-1, socket.getInputStream().read(), "a frame before the handshake");
        }
        try (Socket socket = socket(world)) {
            new DataOutputStream(socket.getOutputStream()).writeInt(Integer.MAX_VALUE);
            assertEquals(-1, socket.getInputStream().read(), "a frame longer than the largest");
        }
        try (Socket socket = socket(world)) {
            writeHandshake(socket, 1, 3, 2, 2);
            assertEquals(-1, socket.getInputStream().read(), "slot 2 of 2");
        }
        try (Socket socket = socket(world)) {
            writeHandshake(socket, 1, 1, 0, 1);
            assertEquals(-1, socket.getInputStream().read(), "this node's own id");
        }
        try (Socket socket = socket(world)) {
            writeHandshake(socket, 1, 3, 0, 1);
            assertEquals(0, readFrame(new DataInputStream(socket.getInputStream()))[0], "a good handshake is answered");
            await(() -> world.peer(3) != null && world.peer(3).isConnected());
        }
        try (Socket silent = socket(world)) {
            long start = System.nanoTime();
            assertEquals(-1, silent.getInputStream().read(), "a connection that never shakes hands is closed");
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test void heartbeatsKeepAnIdleConnectionUp() throws Exception {
        heartbeat = Duration.ofMillis(100);
        RpcPeer peer = connect(1);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < end) {
            assertTrue(peer.isConnected() && world.peer(2).isConnected(), "the idle connection was dropped");
            Thread.sleep(10);
        }
        peer.call(RpcRequest.of(ECHO, text("still")), recorder());
        assertEquals("ok world got still from 2", next(answers));
    }

    @Test void theDialingSideDropsAPeerThatStopsAnsweringHeartbeats() throws Exception {
        heartbeat = Duration.ofMillis(100);
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket silentNode = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> { // shakes hands as node 1, then reads and never answers
                while (true) {
                    try {
                        Socket connection = silentNode.accept();
                        accepted.incrementAndGet();
                        Thread.ofVirtual().start(() -> {
                            try (connection) {
                                readFrame(new DataInputStream(connection.getInputStream()));
                                writeHandshake(connection, 1, 1, 0, 1);
                                connection.getInputStream().transferTo(OutputStream.nullOutputStream());
                            } catch (IOException gone) {}
                        });
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            game = start(node(2, "game").redialDelay(Duration.ofMillis(50), Duration.ofMillis(50)));
            RpcPeer peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", silentNode.getLocalPort()));
            await(peer::isConnected);
            long start = System.nanoTime();
            await(() -> accepted.get() >= 2); // dropped, and dialed again
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
    }

    @Test void theAcceptingSideClosesADialerThatGoesSilent() throws Exception {
        heartbeat = Duration.ofMillis(100);
        world = start(node(1, "world").listen(LOOPBACK));
        try (Socket socket = socket(world)) {
            writeHandshake(socket, 1, 3, 0, 1); // shakes hands, then sends no heartbeat
            DataInputStream in = new DataInputStream(socket.getInputStream());
            assertEquals(0, readFrame(in)[0]);
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> { while (true) readFrame(in); });
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "closed after three of the world's intervals of 100 ms");
        }
    }

    @Test void theAcceptingSideAnswersHeartbeats() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        try (Socket socket = socket(world)) {
            writeHandshake(socket, 1, 3, 0, 1);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            assertEquals(0, readFrame(in)[0]);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(1);
            out.writeByte(1);
            out.flush();
            byte[] answer = readFrame(in);
            assertEquals(1, answer.length);
            assertEquals(1, answer[0], "a heartbeat back");
        }
    }

    @Test void removingAPeerFailsItsCallsAndStopsDialingIt() throws Exception {
        RpcPeer peer = connect(1);
        peer.call(RpcRequest.of(SILENT, text("")), recorder());
        await(() -> peer.pendingCount() == 1);
        assertTrue(game.removePeer(1));
        assertEquals("on the caller fail " + RPC_UNAVAILABLE, next(answers), "failed by the removal, on its thread");
        assertFalse(game.removePeer(1));
        assertNull(game.peer(1));
        await(() -> world.peer(2) != null && !world.peer(2).isConnected());
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));

        RpcPeer dialingIn = world.peer(2);
        assertTrue(world.removePeer(2));
        game.createPeer(1, at(world));
        await(() -> world.peer(2) != null && world.peer(2).isConnected());
        assertNotSame(dialingIn, world.peer(2), "a node dialing in after its removal joins as a new peer");
    }

    @Test void closingTheNodeFailsItsCallsAndLaterOnes() throws Exception {
        RpcPeer peer = connect(1);
        peer.call(RpcRequest.of(SILENT, text("")), recorder());
        await(() -> peer.pendingCount() == 1);
        game.close();
        assertEquals("on the caller fail " + RPC_UNAVAILABLE, next(answers));
        game.close();
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "off the calling thread even once closed");
        assertThrows(IllegalStateException.class, () -> game.createPeer(3, at(world)));
        await(() -> !world.peer(2).isConnected());
    }

    @Test void blockingCallsAreRefusedOnTheNodesOwnThreads() throws Exception {
        RpcPeer peer = connect(1);
        AtomicReference<Throwable> refused = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        peer.call(RpcRequest.of(ECHO, text("")), (request, response) -> {
            try {
                game.close();
            } catch (IllegalStateException e) {
                refused.set(e);
            }
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(refused.get() instanceof IllegalStateException);

        AtomicReference<Throwable> onTimer = new AtomicReference<>();
        CountDownLatch timedOut = new CountDownLatch(1);
        peer.call(RpcRequest.of(SILENT, text("")), Duration.ofMillis(20), (request, response) -> { // on the time wheel's thread
            try {
                game.close();
            } catch (IllegalStateException e) {
                onTimer.set(e);
            }
            timedOut.countDown();
        });
        assertTrue(timedOut.await(5, TimeUnit.SECONDS));
        assertTrue(onTimer.get() instanceof IllegalStateException);
        peer.call(RpcRequest.of(ECHO, text("open")), recorder());
        assertEquals("ok world got open from 2", next(answers), "the node is still open");
    }

    @Test void callsBeyondTheLimitFail() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game").maxPendingCalls(2));
        RpcPeer peer = game.createPeer(1, at(world));
        await(peer::isConnected);
        for (int i = 0; i < 3; i++) peer.call(RpcRequest.of(SILENT, text("")), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
        assertEquals(2, peer.pendingCount());
    }

    /**
     * Two peers join on two EventLoops at once, and each one's listener, run while its peer is being joined, makes
     * the first call to the other: neither may wait for the other.
     */
    @Test void listenersCallingEachOtherDoNotDeadlock() throws Exception {
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory()); // one per connection, in turn
        closeables.add(() -> {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        });
        CyclicBarrier together = new CyclicBarrier(2);
        world = start(node(1, "world").serverBootstrap(new ServerBootstrap().group(boss, workers)).listen(LOOPBACK)
                .listener(new RpcListener() {
                    @Override public void onConnected(RpcPeer peer, boolean restarted) {
                        try {
                            together.await(5, TimeUnit.SECONDS); // both listeners run at once
                        } catch (Exception e) {
                            answers.add("apart " + e);
                            return;
                        }
                        world.peer(peer.nodeId() == 2 ? 3 : 2).call(RpcRequest.of(ECHO, text("hi")), recorder());
                    }
                }));
        start(node(2, "first")).createPeer(1, at(world));
        start(node(3, "second")).createPeer(1, at(world));
        assertEquals(List.of("ok first got hi from 1", "ok second got hi from 1"), sorted(next(answers), next(answers)));
    }

    /** An answer written just before the node closes still reaches its caller: close lets what was written go out. */
    @Test void closingLetsWhatWasWrittenGoOut() throws Exception {
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        closeables.add(() -> {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        });
        world = start(node(1, "world").serverBootstrap(new ServerBootstrap().group(boss, worker)).listen(LOOPBACK));
        game = start(node(2, "game"));
        RpcPeer peer = game.createPeer(1, at(world));
        await(peer::isConnected);
        peer.call(RpcRequest.of(HOLD, text("")), Duration.ofSeconds(3), recorder());
        Held request = next(held);

        CountDownLatch release = new CountDownLatch(1);
        worker.execute(() -> { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertEquals(WriteStatus.ACCEPTED, RpcResponse.of(request.requestId(), text("bye")).writeTo(request.connection()));
        Thread closing = Thread.ofVirtual().start(world::close); // queued behind the answer while the EventLoop is held
        sleep(200);
        release.countDown();
        assertEquals("ok bye", next(answers));
        closing.join();
    }

    @Test void aBusyConnectionRefusesCallsAndNotifies() throws Exception {
        EventLoopGroup loop = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        closeables.add(() -> loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly());
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game").bootstrap(new Bootstrap().group(loop).channel(NioSocketChannel.class)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1000, 2000))));
        RpcPeer peer = game.createPeer(1, at(world));
        await(peer::isConnected);
        CountDownLatch release = new CountDownLatch(1);
        loop.execute(() -> { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        try {
            for (int i = 0; i < 5; i++) peer.notify(RpcNotify.of(NOTE, text("n".repeat(1000)))); // queued while the EventLoop is held
            assertEquals(WriteStatus.NOT_WRITABLE, peer.notify(RpcNotify.of(NOTE, text(""))));
            peer.call(RpcRequest.of(ECHO, text("")), recorder());
        } finally {
            release.countDown();
        }
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "refused, and failed on the EventLoop once it is free");
        await(() -> peer.notify(RpcNotify.of(NOTE, text("again"))) == WriteStatus.ACCEPTED);
    }

    @Test void exceptionsInCallbacksAndListenersAreOnlyLogged() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK).listener(new RpcListener() {
            @Override public void onConnected(RpcPeer peer, boolean restarted) { throw new IllegalStateException("listener"); }
        }));
        game = start(node(2, "game"));
        RpcPeer peer = game.createPeer(1, at(world));
        await(() -> peer.isConnected() && world.peer(2) != null && world.peer(2).isConnected());
        peer.call(RpcRequest.of(ECHO, text("")), (request, response) -> { throw new IllegalStateException("callback"); });
        peer.call(RpcRequest.of(ECHO, text("fine")), recorder());
        assertEquals("ok world got fine from 2", next(answers));
    }

    @Test void misuseThrowsAndReleases() throws Exception {
        assertThrows(IllegalStateException.class, () -> RpcNode.builder().start());
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().maxFrameLength(10));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().maxPendingCalls(0));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().heartbeatInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RpcResponse.failure(1, 0));
        game = start(node(2, "game"));
        RpcPeer peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", freePort()));
        ByteBuf body = text("x");
        assertThrows(IllegalArgumentException.class, () -> peer.call(RpcRequest.of(ECHO, body), Duration.ZERO, recorder()));
        assertEquals(0, body.refCnt());
        ByteBuf other = text("x");
        assertThrows(NullPointerException.class, () -> peer.call(RpcRequest.of(ECHO, other), null));
        assertEquals(0, other.refCnt());
    }

    @Test void theNodeSaysWhoItIs() {
        game = start(node(2, "game").metadata(Metadata.builder().put(SERVICE, 10).build()));
        assertEquals(2, game.nodeId());
        assertEquals(-1, game.port());
        assertEquals(10, game.metadata().get(SERVICE));
        assertNotEquals(0, game.incarnation());
        assertTrue(game.peers().isEmpty());
        assertSame(null, game.peer(1));
    }

    // Helpers

    /** A proxy between the game and the world, which cuts every connection on demand and may point elsewhere. */
    private static final class Proxy implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private volatile int target;

        Proxy(int target) throws IOException {
            this.target = target;
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket in = server.accept();
                        Socket out = new Socket("127.0.0.1", this.target);
                        sockets.add(in);
                        sockets.add(out);
                        pipe(in, out);
                        pipe(out, in);
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        void target(int port) { target = port; }

        void cut() throws IOException {
            for (Socket socket : sockets) socket.close();
            sockets.clear();
        }

        private static void pipe(Socket from, Socket to) {
            Thread.ofVirtual().start(() -> {
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    in.transferTo(out);
                } catch (IOException gone) {
                } finally {
                    try {
                        from.close();
                        to.close();
                    } catch (IOException ignored) {}
                }
            });
        }

        @Override public void close() throws IOException {
            server.close();
            cut();
        }
    }

    private static Socket socket(RpcNode node) throws IOException {
        Socket socket = new Socket("127.0.0.1", node.port());
        socket.setSoTimeout(5000);
        return socket;
    }

    /** A handshake frame inside the length framing, with no metadata. */
    private static void writeHandshake(Socket socket, int version, int nodeId, int slot, int slots) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(20);
        out.writeByte(0);
        out.writeByte(version);
        out.writeInt(nodeId);
        out.writeLong(System.currentTimeMillis() * 1000);
        out.writeShort(slot);
        out.writeShort(slots);
        out.writeShort(0);
        out.flush();
    }

    /** The bytes of the next frame, its type first. */
    private static byte[] readFrame(DataInputStream in) throws IOException {
        byte[] frame = new byte[in.readInt()];
        in.readFully(frame);
        return frame;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ByteBuf text(String text) { return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8); }

    private static String text(ByteBuf body) { return body.toString(StandardCharsets.UTF_8); }

    private static List<String> sorted(String... items) { return List.of(items).stream().sorted().toList(); }

    private static <T> T next(BlockingQueue<T> queue) throws InterruptedException {
        T item = queue.poll(10, TimeUnit.SECONDS);
        if (item == null) fail("Nothing arrived in time");
        return item;
    }

    private static List<String> drain(BlockingQueue<String> queue) {
        List<String> items = new ArrayList<>();
        queue.drainTo(items);
        return items;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting");
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
