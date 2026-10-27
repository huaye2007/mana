package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_NODE_CLOSED;
import static cn.managame.FrameworkErrorCodes.RPC_PEER_REMOVED;
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
import cn.managame.network.netty.NetworkSsl;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ResourceLeakDetector;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Node 1, a world server, listens; node 2, a game server that does not listen, dials it. */
class RpcNodeTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);
    private static final MetadataKey<Integer> SERVICE = MetadataKey.intKey(2);
    private static final MetadataKey<String> TOKEN = MetadataKey.stringKey(3);
    private static final int ECHO = 1, FIELDS = 2, LATER = 3, KEEP = 4, REFUSE = 5, BOOM = 6, SILENT = 7, TWICE = 8,
            NOTE = 9, BIG = 10, DEADLINE = 11;

    /** What handlers and listeners saw, and what callbacks got. */
    private final BlockingQueue<String> seen = new LinkedBlockingQueue<>(), answers = new LinkedBlockingQueue<>();
    private final List<RpcNode> nodes = new ArrayList<>();
    private Duration timeout = RpcNode.DEFAULT_TIMEOUT, idleTimeout = RpcNode.DEFAULT_IDLE_TIMEOUT;
    private int maxFrameLength = RpcNode.DEFAULT_MAX_FRAME_LENGTH;
    private RpcNode world, game;

    @BeforeAll static void detectLeaks() { ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID); }

    @AfterEach void closeNodes() { nodes.forEach(RpcNode::close); }

    /** A handler as an application writes one: it decides by the message and its command what to do. */
    private RpcHandler handler(String name) {
        return (rpcPeer, connection, msg) -> {
            switch (msg) {
                case RpcRequest request -> answer(name, rpcPeer, connection, request);
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
            case FIELDS -> RpcResponse.of(id, text(request.routeKey() + " " + request.businessType() + " "
                    + request.businessId() + " " + request.metadata().get(TRACE) + " " + (id != 0))).writeTo(connection);
            case LATER -> Thread.ofVirtual().start(() -> {
                sleep(300);
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
            case TWICE -> {
                RpcResponse.of(id, text("first")).writeTo(connection);
                RpcResponse.of(id, text("second")).writeTo(connection);
            }
            case BIG -> RpcResponse.of(id, text("y".repeat(2000))).writeTo(connection);
            case DEADLINE -> {
                boolean expiredAtFirst = request.isExpired(), hasDeadline = request.deadline() != 0;
                Thread.ofVirtual().start(() -> {
                    sleep(300);
                    seen.add(name + " deadline " + hasDeadline + " " + expiredAtFirst + " " + request.isExpired());
                });
            }
            case REFUSE -> RpcResponse.failure(id, 9001).writeTo(connection);
            case BOOM -> throw new IllegalStateException("boom");
            case SILENT -> seen.add(name + " silent");
            default -> RpcResponse.failure(id, HANDLER_NOT_FOUND).writeTo(connection);
        }
    }

    private RpcCallback recorder() {
        return (request, response) -> answers.add(response.isSuccess() ? "ok " + text(response.body()) : "fail " + response.code());
    }

    private RpcNode.Builder node(int id, String name) {
        return RpcNode.builder().nodeId(id).timeout(timeout).idleTimeout(idleTimeout).maxFrameLength(maxFrameLength)
                .handler(handler(name));
    }

    private RpcNode start(RpcNode.Builder builder) {
        RpcNode node = builder.start();
        nodes.add(node);
        return node;
    }

    /** Node 1 listening, node 2 dialing it with {@code connections} connections, both handshakes done. */
    private void connect(int connections) throws InterruptedException {
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game"));
        game.createPeer(1, address(world), connections);
        await(() -> game.peer(1).connectionCount() == connections
                && world.peer(2) != null && world.peer(2).connectionCount() == connections);
    }

    private static InetSocketAddress address(RpcNode node) { return new InetSocketAddress("127.0.0.1", node.port()); }

    private static ByteBuf text(String text) { return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8); }

    private static String text(ByteBuf body) { return body.toString(StandardCharsets.UTF_8); }

    private static String next(BlockingQueue<String> queue) throws InterruptedException { return queue.poll(5, TimeUnit.SECONDS); }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("The condition never held");
            Thread.sleep(10);
        }
    }

    @Test void aCallIsAnsweredAndItsCallbackKeepsTheRequest() throws Exception {
        connect(1);
        Metadata trace = Metadata.builder().put(TRACE, "t1").build();
        game.peer(1).call(RpcRequest.of(ECHO, 1001, (byte) 3, 77, trace, text("hi")), (request, response) -> answers.add(
                request.command() + " " + request.routeKey() + " " + request.businessType() + " " + request.businessId()
                        + " " + request.metadata().get(TRACE) + " " + request.body().readableBytes()
                        + " " + (request.requestId() != 0 && request.requestId() == response.requestId())
                        + " | " + response.code() + " " + response.metadata().get(TRACE) + " " + text(response.body())));
        assertEquals("1 1001 3 77 t1 0 true | 0 t1 world got hi from 2", next(answers),
                "the callback gets the request it answers, without the body that was sent");

        game.peer(1).call(RpcRequest.of(FIELDS, 1001, (byte) 3, 77, trace, Unpooled.EMPTY_BUFFER), recorder());
        assertEquals("ok 1001 3 77 t1 true", next(answers), "the callee sees every field");
        game.peer(1).call(RpcRequest.of(LATER, text("x")), recorder());
        assertEquals("ok world later x", next(answers), "answered from another thread after the handler returned");
        game.peer(1).call(RpcRequest.of(KEEP, text("y")), recorder());
        assertEquals("ok world kept y", next(answers), "a retained request outlives the handler");
    }

    @Test void failuresReachTheCallbackAsCodes() throws Exception {
        connect(1);
        game.peer(1).call(RpcRequest.of(REFUSE, text("")), recorder());
        assertEquals("fail 9001", next(answers));
        game.peer(1).call(RpcRequest.of(BOOM, text("")), recorder());
        assertEquals("fail " + RPC_HANDLER_ERROR, next(answers), "the handler threw");
        game.peer(1).call(RpcRequest.of(99, text("")), recorder());
        assertEquals("fail " + HANDLER_NOT_FOUND, next(answers), "the handler's own answer");

        game.peer(1).call(RpcRequest.of(TWICE, text("")), recorder());
        assertEquals("ok first", next(answers));
        assertNull(answers.poll(200, TimeUnit.MILLISECONDS), "only the first answer counts");
        assertThrows(IllegalArgumentException.class, () -> RpcResponse.failure(1, 0));
    }

    @Test void aNodeWithoutAHandlerFailsRequestsAndDropsNotifies() throws Exception {
        world = start(RpcNode.builder().nodeId(1).listen(LOOPBACK));
        game = start(node(2, "game"));
        RpcPeer peer = game.createPeer(1, address(world));
        await(peer::isConnected);
        assertTrue(peer.notify(RpcNotify.of(NOTE, text("dropped"))));
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + HANDLER_NOT_FOUND, next(answers));
    }

    @Test void aCallNobodyAnswersTimesOut() throws Exception {
        timeout = Duration.ofMillis(300);
        connect(1);
        game.peer(1).call(RpcRequest.of(SILENT, text("")), recorder());
        assertEquals("world silent", next(seen));
        assertEquals("fail " + RPC_TIMEOUT, next(answers), "after the node's timeout");
        game.peer(1).call(RpcRequest.of(SILENT, text("")), Duration.ofMillis(50), recorder());
        assertEquals("fail " + RPC_TIMEOUT, next(answers), "after the call's own timeout");
    }

    @Test void theCalleeSeesTheCallersDeadline() throws Exception {
        connect(1);
        game.peer(1).call(RpcRequest.of(DEADLINE, text("")), Duration.ofMillis(200), recorder());
        assertEquals("fail " + RPC_TIMEOUT, next(answers));
        assertEquals("world deadline true false true", next(seen), "not expired on arrival, expired once the caller gave up");
    }

    @Test void callsAndNotifiesGoBothWaysOverTheConnectionsTheDialerOpened() throws Exception {
        connect(1); // node 2 does not listen
        assertTrue(game.peer(1).isOutbound());
        assertFalse(world.peer(2).isOutbound());
        world.peer(2).call(RpcRequest.of(ECHO, text("mail")), recorder());
        assertEquals("ok game got mail from 1", next(answers));
        assertTrue(world.peer(2).notify(RpcNotify.of(NOTE, text("news"))));
        assertEquals("game notified news from 1", next(seen));
        assertTrue(game.peer(1).notify(RpcNotify.of(NOTE, text("hello"))));
        assertEquals("world notified hello from 2", next(seen));
    }

    @Test void aRouteKeyKeepsToOneOfSeveralConnections() throws Exception {
        connect(3);
        for (int i = 0; i < 100; i++) game.peer(1).notify(RpcNotify.of(NOTE, 1001, text("n" + i)));
        List<String> received = new ArrayList<>();
        for (int i = 0; i < 100; i++) received.add(next(seen));
        assertEquals(IntStream.range(0, 100).mapToObj(i -> "world notified n" + i + " from 2").toList(), received);
    }

    @Test void aBroadcastSendsOneBodyToEveryPeer() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        for (int id : new int[] {2, 3}) start(node(id, "game " + id)).createPeer(1, address(world));
        await(() -> world.peers().size() == 2 && world.peers().stream().allMatch(RpcPeer::isConnected));
        ByteBuf body = text("news");
        assertEquals(2, world.broadcast(world.peers(), RpcNotify.of(NOTE, body)));
        assertEquals(Set.of("game 2 notified news from 1", "game 3 notified news from 1"), Set.of(next(seen), next(seen)));
        await(() -> body.refCnt() == 0);
    }

    /** What a node is for is the application's: it puts it in the handshake and groups the peers itself. */
    @Test void anApplicationGroupsPeersByWhatTheySayInTheirHandshake() throws Exception {
        int worldService = 1, gameService = 2, gateService = 3;
        Map<Integer, Set<RpcPeer>> byService = new ConcurrentHashMap<>();
        RpcListener grouping = new RpcListener() {
            @Override public void onConnected(RpcPeer peer, boolean restarted) {
                byService.computeIfAbsent(peer.metadata().get(SERVICE), service -> ConcurrentHashMap.newKeySet()).add(peer);
            }

            @Override public void onDisconnected(RpcPeer peer) { byService.get(peer.metadata().get(SERVICE)).remove(peer); }
        };
        world = start(node(1, "world").metadata(Metadata.builder().put(SERVICE, worldService).build())
                .listen(LOOPBACK).listener(grouping));
        List<RpcNode> games = new ArrayList<>();
        for (int id : new int[] {2, 3}) games.add(start(node(id, "game " + id).metadata(Metadata.builder().put(SERVICE, gameService).build())));
        RpcNode gate = start(node(4, "gate").metadata(Metadata.builder().put(SERVICE, gateService).build()));
        for (RpcNode dialer : List.of(games.get(0), games.get(1), gate)) dialer.createPeer(1, address(world));
        await(() -> byService.getOrDefault(gameService, Set.of()).size() == 2 && byService.getOrDefault(gateService, Set.of()).size() == 1);
        assertEquals(worldService, gate.peer(1).metadata().get(SERVICE), "each side reads the other's");

        // The application picks a game server for a key in its own way, here by the key modulo the node ids in order.
        List<RpcPeer> gameServers = byService.get(gameService).stream().sorted((a, b) -> a.nodeId() - b.nodeId()).toList();
        gameServers.get(Math.floorMod(1001L, gameServers.size())).call(RpcRequest.of(ECHO, 1001, text("guild")), recorder());
        assertEquals("ok game 3 got guild from 1", next(answers));

        games.get(1).close();
        await(() -> byService.get(gameService).size() == 1);
    }

    @Test void aRestartIsToldFromAReconnect() throws Exception {
        RpcListener log = new RpcListener() {
            @Override public void onConnected(RpcPeer peer, boolean restarted) { seen.add("up " + peer.nodeId() + (restarted ? " restarted" : "")); }

            @Override public void onDisconnected(RpcPeer peer) { seen.add("down " + peer.nodeId()); }
        };
        world = start(node(1, "world").listen(LOOPBACK).listener(log));
        try (Proxy network = new Proxy(world.port())) {
            game = start(node(2, "game"));
            game.createPeer(1, network.address(), 2);
            assertEquals("up 2", next(seen));
            await(() -> world.peer(2).connectionCount() == 2);
            assertNull(seen.poll(200, TimeUnit.MILLISECONDS), "once per peer, not per connection");
            RpcPeer peer = world.peer(2);
            assertEquals(game.incarnation(), peer.incarnation());

            network.cut();
            assertEquals("down 2", next(seen));
            assertEquals("up 2", next(seen), "the same instance came back");
            assertSame(peer, world.peer(2), "and rejoined the same peer");

            game.close();
            assertEquals("down 2", next(seen));
            assertSame(peer, world.peer(2), "a disconnected peer stays known");
            RpcNode again = start(node(2, "game again"));
            again.createPeer(1, network.address());
            assertEquals("up 2 restarted", next(seen), "a new instance");
            assertSame(peer, world.peer(2));
            assertEquals(again.incarnation(), peer.incarnation());
            peer.call(RpcRequest.of(ECHO, text("back")), recorder());
            assertEquals("ok game again got back from 1", next(answers));
        }
    }

    @Test void aNewerInstanceUnderTheSameIdReplacesTheOlderOne() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        RpcNode older = start(node(2, "older"));
        older.createPeer(1, address(world));
        await(() -> world.peer(2) != null && world.peer(2).isConnected());
        Thread.sleep(5); // a later start, so a larger incarnation
        RpcNode newer = start(node(2, "newer"));
        newer.createPeer(1, address(world));
        await(() -> world.peer(2).incarnation() == newer.incarnation() && newer.peer(1).isConnected());
        Thread.sleep(300); // the older one dials again and is refused
        assertFalse(older.peer(1).isConnected());
        for (int key = 0; key < 10; key++) world.peer(2).call(RpcRequest.of(ECHO, key, text("who")), recorder());
        for (int key = 0; key < 10; key++) assertEquals("ok newer got who from 1", next(answers));
    }

    @Test void twoNodesThatDialEachOtherAreRefused() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game").listen(LOOPBACK));
        world.createPeer(2, address(game));
        game.createPeer(1, address(world));
        Thread.sleep(500);
        assertFalse(world.peer(2).isConnected());
        assertFalse(game.peer(1).isConnected());
    }

    @Test void aNodeAnsweringUnderAnotherIdIsNotJoined() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game"));
        RpcPeer five = game.createPeer(5, address(world));
        Thread.sleep(500);
        assertFalse(five.isConnected(), "node 1 answered, not node 5");
        assertNull(game.peer(1));
    }

    @Test void theAuthenticatorDecidesWhoJoins() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK)
                .authenticator((connection, nodeId, metadata) -> "secret".equals(metadata.get(TOKEN))));
        Metadata secret = Metadata.builder().put(TOKEN, "secret").build();
        RpcPeer trusted = start(node(2, "trusted").metadata(secret)).createPeer(1, address(world));
        RpcPeer stranger = start(node(3, "stranger").metadata(Metadata.builder().put(TOKEN, "guess").build()))
                .createPeer(1, address(world));
        RpcPeer picky = start(node(4, "picky").metadata(secret).authenticator((connection, nodeId, metadata) -> false))
                .createPeer(1, address(world));
        await(trusted::isConnected);
        Thread.sleep(300);
        assertFalse(stranger.isConnected());
        assertNull(world.peer(3), "a refused node leaves no peer behind");
        assertFalse(picky.isConnected(), "the dialing side asks too");
    }

    @Test void connectionsCanUseTls(@TempDir Path directory) throws Exception {
        char[] password = "secret".toCharArray();
        Path keys = keyStore(directory, password);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(keys.toFile(), password));
        SslContext client = SslContextBuilder.forClient().trustManager(trust).build();
        world = start(node(1, "world").listen(LOOPBACK).serverSsl(NetworkSsl.server(keys, password)));
        game = start(node(2, "game").clientSsl(client));
        RpcPeer peer = game.createPeer(1, address(world));
        await(peer::isConnected);
        peer.call(RpcRequest.of(ECHO, text("secure")), recorder());
        assertEquals("ok world got secure from 2", next(answers));

        RpcPeer plain = start(node(3, "plain")).createPeer(1, address(world));
        Thread.sleep(300);
        assertFalse(plain.isConnected(), "a node without TLS is not let in");
    }

    /** A PKCS#12 key store for 127.0.0.1, made with the JDK's keytool. */
    private static Path keyStore(Path directory, char[] password) throws Exception {
        Path store = directory.resolve("node.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.exists(keytool)) keytool = Path.of(keytool + ".exe");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "node", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(password), "-keypass", new String(password))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "keytool failed: " + output);
        return store;
    }

    @Test void nodesAgreeOnAProtocolVersionTheyBothSpeak() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        try (Socket socket = new Socket("127.0.0.1", world.port())) {
            socket.setSoTimeout(5000);
            writeHello(new DataOutputStream(socket.getOutputStream()), 1, 5, 2);
            assertEquals(0, readFrame(new DataInputStream(socket.getInputStream()))[0], "a newer node that also speaks 1 is answered");
            await(() -> world.peer(2) != null && world.peer(2).isConnected());
        }
        try (Socket socket = new Socket("127.0.0.1", world.port())) {
            socket.setSoTimeout(5000);
            writeHello(new DataOutputStream(socket.getOutputStream()), 2, 3, 3);
            assertEquals(-1, socket.getInputStream().read(), "one that does not is closed without an answer");
        }
        assertNull(world.peer(3));
    }

    @Test void aFrameBeforeTheHelloClosesTheConnection() throws Exception {
        world = start(node(1, "world").listen(LOOPBACK));
        try (Socket socket = new Socket("127.0.0.1", world.port())) {
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(1);
            out.writeByte(4); // a Ping
            out.flush();
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test void aConnectionThatNeverSaysHelloIsClosed() throws Exception {
        idleTimeout = Duration.ofMillis(300);
        world = start(node(1, "world").listen(LOOPBACK));
        try (Socket silent = new Socket("127.0.0.1", world.port())) {
            silent.setSoTimeout(5000);
            assertEquals(-1, silent.getInputStream().read(), "no Pings before the handshake, then the end of the stream");
        }
    }

    @Test void pingsKeepAnIdleConnectionUp() throws Exception {
        idleTimeout = Duration.ofMillis(300);
        connect(1);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < end) { // a dropped connection would be down for the 100 ms before redialing
            assertTrue(game.peer(1).isConnected() && world.peer(2).isConnected(), "the idle connection was dropped");
            Thread.sleep(10);
        }
        game.peer(1).call(RpcRequest.of(ECHO, text("still")), recorder());
        assertEquals("ok world got still from 2", next(answers));
    }

    @Test void aPeerThatFallsSilentIsDroppedAndItsCallsFailAtOnce() throws Exception {
        idleTimeout = Duration.ofMillis(300);
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket silentNode = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> { // answers the Hello as node 1, then neither answers nor pings
                while (true) {
                    try {
                        Socket connection = silentNode.accept();
                        accepted.incrementAndGet();
                        Thread.ofVirtual().start(() -> {
                            try (connection) {
                                DataInputStream in = new DataInputStream(connection.getInputStream());
                                readFrame(in);
                                writeHello(new DataOutputStream(connection.getOutputStream()), 1, 1, 1);
                                in.transferTo(OutputStream.nullOutputStream());
                            } catch (IOException gone) {}
                        });
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            game = start(node(2, "game"));
            RpcPeer peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", silentNode.getLocalPort()));
            await(peer::isConnected);
            peer.call(RpcRequest.of(ECHO, text("")), Duration.ofSeconds(10), recorder());
            assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "long before the call's 10-second timeout");
            await(() -> accepted.get() >= 2); // and the node dials again
        }
    }

    /** A Hello frame inside the length framing, with no metadata. */
    private static void writeHello(DataOutputStream out, int minVersion, int maxVersion, int nodeId) throws IOException {
        out.writeInt(17);
        out.writeByte(0);
        out.writeByte(minVersion);
        out.writeByte(maxVersion);
        out.writeInt(nodeId);
        out.writeLong(1);
        out.writeShort(0);
        out.flush();
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        byte[] frame = new byte[in.readInt()];
        in.readFully(frame);
        return frame;
    }

    @Test void aPeerIsUnavailableUntilItCanBeReached() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        game = start(node(2, "game").redialDelay(Duration.ofMillis(20), Duration.ofMillis(200)));
        RpcPeer peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", port));
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
        assertFalse(peer.notify(RpcNotify.of(NOTE, text("lost"))));

        world = start(node(1, "world").listen(new InetSocketAddress("127.0.0.1", port)));
        await(peer::isConnected); // the node keeps dialing
        peer.call(RpcRequest.of(ECHO, text("now")), recorder());
        assertEquals("ok world got now from 2", next(answers));
    }

    @Test void aMessageLargerThanAFrameFailsAloneAndTheConnectionStays() throws Exception {
        maxFrameLength = 1024;
        connect(1);
        game.peer(1).call(RpcRequest.of(ECHO, text("x".repeat(2000))), recorder());
        assertEquals("fail " + RPC_PROTOCOL_ERROR, next(answers), "a request too large");
        assertFalse(game.peer(1).notify(RpcNotify.of(NOTE, text("x".repeat(2000)))), "a notify too large");
        game.peer(1).call(RpcRequest.of(BIG, text("")), recorder());
        assertEquals("fail " + RPC_PROTOCOL_ERROR, next(answers), "an answer too large");
        game.peer(1).call(RpcRequest.of(ECHO, text("small")), recorder());
        assertEquals("ok world got small from 2", next(answers), "the connection survived");
        assertEquals(0, world.unansweredCount());
    }

    @Test void anObserverSeesEveryCallEndAndTheCountsShowWhatWaits() throws Exception {
        List<String> observed = new CopyOnWriteArrayList<>();
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game").observer((peer, request, response, elapsedNanos) ->
                observed.add(peer.nodeId() + " " + request.command() + " " + response.code() + " " + (elapsedNanos >= 0))));
        RpcPeer peer = game.createPeer(1, address(world));
        await(peer::isConnected);
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        next(answers);
        peer.call(RpcRequest.of(REFUSE, text("")), recorder());
        next(answers);
        assertEquals(List.of("1 " + ECHO + " 0 true", "1 " + REFUSE + " 9001 true"), observed);

        peer.call(RpcRequest.of(SILENT, text("")), Duration.ofSeconds(10), recorder());
        assertEquals("world silent", next(seen));
        assertEquals(1, peer.pendingCount());
        assertEquals(1, world.unansweredCount());
    }

    @Test void aClosingNodeDrainsBeforeItCloses() throws Exception {
        RpcListener log = new RpcListener() {
            @Override public void onClosing(RpcPeer peer) { seen.add("closing " + peer.nodeId()); }
        };
        world = start(node(1, "world").listen(LOOPBACK));
        game = start(node(2, "game").listener(log));
        RpcPeer peer = game.createPeer(1, address(world));
        await(peer::isConnected);
        peer.call(RpcRequest.of(LATER, text("work")), recorder()); // answered 300 ms later
        await(() -> world.unansweredCount() == 1);
        Thread closer = Thread.ofVirtual().start(() -> world.close(Duration.ofSeconds(5)));
        assertEquals("closing 1", next(seen));
        assertTrue(peer.isClosing());
        peer.call(RpcRequest.of(ECHO, text("new")), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "nothing new for a closing node");
        assertEquals("ok world later work", next(answers), "but what it had is answered");
        closer.join(5000);
        assertFalse(closer.isAlive());
        await(() -> !peer.isConnected());
    }

    @Test void removingAPeerFailsItsCallsAndForgetsIt() throws Exception {
        connect(1);
        RpcPeer peer = game.peer(1);
        peer.call(RpcRequest.of(SILENT, text("")), recorder());
        assertEquals("world silent", next(seen));
        assertTrue(game.removePeer(1));
        assertEquals("fail " + RPC_PEER_REMOVED, next(answers), "the call that waited");
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + RPC_PEER_REMOVED, next(answers), "a call through the removed peer");
        assertFalse(peer.notify(RpcNotify.of(NOTE, text(""))));
        assertNull(game.peer(1));
        assertFalse(game.removePeer(1));
        await(() -> !world.peer(2).isConnected());
        Thread.sleep(300);
        assertFalse(world.peer(2).isConnected(), "node 2 no longer dials node 1");
    }

    @Test void aRemovedPeerThatDialsInAgainIsANewPeer() throws Exception {
        connect(1);
        RpcPeer removed = world.peer(2);
        assertTrue(world.removePeer(2));
        await(() -> world.peer(2) != null && world.peer(2).isConnected()); // node 2 dials again
        assertNotSame(removed, world.peer(2));
        assertFalse(removed.isConnected());
    }

    @Test void aPeerThatGoesAwayFailsItsWaitingCalls() throws Exception {
        connect(1);
        game.peer(1).call(RpcRequest.of(SILENT, text("")), recorder());
        assertEquals("world silent", next(seen));
        world.close();
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
    }

    @Test void closingTheNodeFailsWaitingAndLaterCalls() throws Exception {
        connect(1);
        RpcPeer peer = game.peer(1);
        peer.call(RpcRequest.of(SILENT, text("")), recorder());
        assertEquals("world silent", next(seen));
        game.close();
        assertEquals("fail " + RPC_NODE_CLOSED, next(answers));
        peer.call(RpcRequest.of(ECHO, text("")), recorder());
        assertEquals("fail " + RPC_NODE_CLOSED, next(answers));
        assertFalse(peer.notify(RpcNotify.of(NOTE, text(""))));
        assertThrows(IllegalStateException.class, () -> game.createPeer(3, address(world)));
        game.close(); // idempotent
    }

    /** With the application's own EventLoops and channel types, here Netty's in-process transport. */
    @Test void nodesRunOnTheApplicationsBootstrapsAndTheLocalTransport() throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, LocalIoHandler.newFactory());
        try {
            LocalAddress address = new LocalAddress("world-" + System.nanoTime());
            world = start(node(1, "world").serverBootstrap(new ServerBootstrap().group(group).channel(LocalServerChannel.class))
                    .listen(address));
            game = start(node(2, "game").bootstrap(new Bootstrap().group(group).channel(LocalChannel.class)));
            RpcPeer peer = game.createPeer(1, address);
            await(peer::isConnected);
            peer.call(RpcRequest.of(ECHO, text("local")), recorder());
            assertEquals("ok world got local from 2", next(answers));
            assertEquals(-1, world.port());
            game.close();
            world.close();
            assertFalse(group.isShuttingDown(), "the application's EventLoops stay its own");
            assertFalse(peer.isConnected());
        } finally {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test void theNodeReleasesTheBodiesItIsGiven() throws Exception {
        connect(1);
        ByteBuf sent = text("hi");
        game.peer(1).call(RpcRequest.of(ECHO, sent), recorder());
        assertEquals("ok world got hi from 2", next(answers));
        await(() -> sent.refCnt() == 0);

        RpcPeer unreachable = game.createPeer(9, new InetSocketAddress("127.0.0.1", 1));
        ByteBuf unsent = text("lost"), unsentNotify = text("lost");
        unreachable.call(RpcRequest.of(ECHO, unsent), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
        assertFalse(unreachable.notify(RpcNotify.of(NOTE, unsentNotify)));
        assertEquals(0, unsent.refCnt());
        assertEquals(0, unsentNotify.refCnt());

        ByteBuf invalid = text("x");
        assertThrows(IllegalArgumentException.class, () -> game.peer(1).call(RpcRequest.of(ECHO, invalid), Duration.ZERO, recorder()));
        assertEquals(0, invalid.refCnt(), "also when the call throws");
    }

    @Test void aCallbackOrListenerThatThrowsDoesNotHurtTheNode() throws Exception {
        RpcListener throwing = new RpcListener() {
            @Override public void onConnected(RpcPeer peer, boolean restarted) { throw new IllegalStateException("listener"); }

            @Override public void onDisconnected(RpcPeer peer) { throw new IllegalStateException("listener"); }
        };
        world = start(node(1, "world").listen(LOOPBACK).listener(throwing));
        game = start(node(2, "game").observer((peer, request, response, elapsed) -> { throw new IllegalStateException("observer"); }));
        RpcPeer peer = game.createPeer(1, address(world));
        await(peer::isConnected);
        peer.call(RpcRequest.of(ECHO, text("")), (request, response) -> { throw new IllegalStateException("callback"); });
        peer.call(RpcRequest.of(ECHO, text("fine")), recorder());
        assertEquals("ok world got fine from 2", next(answers));
    }

    @Test void misuseIsRejected() throws Exception {
        connect(1);
        assertThrows(IllegalArgumentException.class, () -> game.createPeer(2, address(world)), "this node");
        assertThrows(IllegalStateException.class, () -> game.createPeer(1, address(world)), "dialed already");
        assertThrows(IllegalStateException.class, () -> world.createPeer(2, address(world)), "node 2 dials in");
        assertThrows(IllegalArgumentException.class, () -> game.createPeer(3, address(world), 0));

        game.peer(1).call(RpcRequest.of(ECHO, text("")), (request, response) -> {
            try {
                game.removePeer(1);
                answers.add("removed on the EventLoop");
            } catch (IllegalStateException e) {
                answers.add("refused on the EventLoop");
            }
        });
        assertEquals("refused on the EventLoop", next(answers));
        game.peer(1).call(RpcRequest.of(SILENT, text("")), Duration.ofMillis(20), (request, response) -> {
            try {
                game.close();
                answers.add("closed on the timer");
            } catch (IllegalStateException e) {
                answers.add("refused on the timer");
            }
        });
        assertEquals("refused on the timer", next(answers));

        assertThrows(IllegalStateException.class, () -> RpcNode.builder().start(), "no node id");
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().idleTimeout(Duration.ofMillis(2)));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().maxFrameLength(10));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().redialDelay(Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().bootstrap(new Bootstrap().handler(new ChannelInboundHandlerAdapter())));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().serverBootstrap(new ServerBootstrap().childHandler(new ChannelInboundHandlerAdapter())));
    }

    /** Forwards connections to a port until cut, as a network that can fail. */
    private static final class Proxy implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        Proxy(int target) throws IOException {
            Thread.ofVirtual().start(() -> {
                while (true) {
                    try {
                        Socket in = listener.accept();
                        Socket out = new Socket(InetAddress.getLoopbackAddress(), target);
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

        InetSocketAddress address() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()); }

        /** Drops every connection through it, as a network failure would; new ones still pass. */
        void cut() throws IOException {
            for (Socket socket : sockets) socket.close();
            sockets.clear();
        }

        private static void pipe(Socket from, Socket to) {
            Thread.ofVirtual().start(() -> {
                try (from; to) {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (IOException gone) {}
            });
        }

        @Override public void close() throws IOException {
            listener.close();
            cut();
        }
    }
}
