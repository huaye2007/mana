package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_NODE_CLOSED;
import static cn.managame.FrameworkErrorCodes.RPC_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.dispatch.HandleInterceptor;
import cn.managame.runtime.executor.RouteExecutors;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Node 1 is a rank server that listens; node 2, a game server, connects to it and calls it from its players'
 * tasks. Node 1 calls node 2 back over the connections node 2 opened.
 */
class RpcNodeTest {
    private static final int PLAYER = 1, RANK = 2;
    private static final byte ROLE = 1;
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);
    private static final InetSocketAddress NOWHERE = new InetSocketAddress("127.0.0.1", 1);

    /** Java serialization, enough for a test; a real cluster would use its own format. */
    private static final RpcCodec CODEC = new RpcCodec() {
        @Override public void encode(Object message, ByteBuf out) throws Exception {
            try (ObjectOutputStream stream = new ObjectOutputStream(new ByteBufOutputStream(out))) { stream.writeObject(message); }
        }
        @Override public Object decode(ByteBuf body) throws Exception {
            try (ObjectInputStream stream = new ObjectInputStream(new ByteBufInputStream(body))) { return stream.readObject(); }
        }
    };

    record RankOf() implements Serializable {}
    record Boom() implements Serializable {}
    record Silent() implements Serializable {}
    record Unencodable() implements Serializable {}
    record Unknown() implements Serializable {}
    record Note(String text) implements Serializable {}
    record EchoBack(String text) implements Serializable {}
    record Deliver(String text) implements Serializable {}
    record AskRank() {}

    private final GameRuntime rankRuntime = GameRuntime.builder()
            .domain(RANK, RouteExecutors.striped(1), RouteKeys.constant(0)).build();
    private final GameRuntime gameRuntime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId()).build();
    /** What the handlers did, and what the callbacks got. */
    private final BlockingQueue<String> handled = new LinkedBlockingQueue<>(), answers = new LinkedBlockingQueue<>();
    private RpcNode rank, game;

    @Domain(RANK)
    class RankService {
        @HandleMethod String rankOf(HandleContext context, RankOf query) {
            return "rank of " + context.businessId() + " asked by node " + ((RpcSource) context.source()).nodeId()
                    + " " + context.metadata().get(TRACE);
        }
        @HandleMethod String boom(Boom boom) { throw new IllegalStateException("boom"); }
        @HandleMethod String silent(Silent silent) {
            handled.add("silent");
            return null; // and never answers
        }
        @HandleMethod Object unencodable(Unencodable query) { return new Object(); }
        @HandleMethod void note(Note note) { handled.add(note.text() + " for " + Context.current().businessId()); }
        @HandleMethod void echoBack(HandleContext context, EchoBack echo) {
            ((RpcSource) context.source()).peer().notice(context, new Deliver(echo.text()));
        }
    }

    @Domain(PLAYER)
    class Mailbox {
        @HandleMethod String deliver(Deliver deliver) {
            String delivered = "delivered " + deliver.text() + " on " + onRoute();
            handled.add(delivered);
            return delivered;
        }
    }

    @AfterEach void stop() {
        if (game != null) game.close();
        if (rank != null) rank.close();
        gameRuntime.close();
        rankRuntime.close();
    }

    private RpcNode startRank(int port) {
        return RpcNode.builder(rankRuntime).nodeId(1).codec(CODEC).listen(new InetSocketAddress("127.0.0.1", port))
                .register(new RankService()).start();
    }

    private RpcNode startGame(int rankPort, int connections, Duration timeout) {
        return RpcNode.builder(gameRuntime).nodeId(2).codec(CODEC).timeout(timeout)
                .peer(1, new InetSocketAddress("127.0.0.1", rankPort), connections)
                .register(new Mailbox()).start();
    }

    private void connect(Duration timeout) throws InterruptedException {
        rank = startRank(0);
        game = startGame(rank.port(), 1, timeout);
        await(() -> game.peer(1).isConnected() && rank.peer(2).isConnected());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("The nodes never connected");
            Thread.sleep(10);
        }
    }

    /** Calls node 1 from player 1001's task; the callbacks record the route they ran on and what they got. */
    private void callAsPlayer(Object request) {
        Metadata traced = Metadata.builder().put(TRACE, "t1").build();
        gameRuntime.execute(new GameContext(PLAYER, 1001, ROLE, 1001, traced), context -> game.peer(1).call(request,
                (c, result) -> answers.add(onRoute() + " " + result),
                (c, errorCode) -> answers.add(onRoute() + " fail " + errorCode)));
    }

    private static String onRoute() { return Context.current().domain() + ":" + Context.current().routingKey(); }

    private static String next(BlockingQueue<String> queue) throws InterruptedException { return queue.poll(5, TimeUnit.SECONDS); }

    @Test void aCallRunsAtThePeerActingForTheCallerAndIsAnsweredOnTheCallersRoute() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        callAsPlayer(new RankOf());
        assertEquals(PLAYER + ":1001 rank of 1001 asked by node 2 t1", next(answers));
    }

    @Test void aPlayerRequestIsAnsweredFromTheCallback() throws Exception {
        @Domain(PLAYER) class Ranking {
            @HandleMethod String ask(HandleContext request, AskRank ask) {
                game.peer(1).call(new RankOf(), (Context c, String rank) -> request.reply(rank), (c, errorCode) -> request.fail(errorCode));
                return null;
            }
        }
        connect(RpcNode.DEFAULT_TIMEOUT);
        Dispatcher players = Dispatcher.builder(gameRuntime).register(new Ranking()).build();
        players.dispatch(GameContext.of(0, 0, ROLE, 7), new AskRank(),
                result -> answers.add("reply " + result), errorCode -> answers.add("fail " + errorCode));
        assertEquals("reply rank of 7 asked by node 2 null", next(answers));
    }

    @Test void callsGoBothWaysOverTheConnectionsAPeerOpened() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT); // node 2 does not listen
        rank.peer(2).call(GameContext.of(RANK, 0, ROLE, 1001), new Deliver("mail"),
                (c, result) -> answers.add("answered " + result), (c, errorCode) -> answers.add("fail " + errorCode));
        assertEquals("delivered mail on " + PLAYER + ":1001", next(handled));
        assertEquals("answered delivered mail on " + PLAYER + ":1001", next(answers));

        // A handler calls back the peer a request came from.
        gameRuntime.execute(GameContext.of(PLAYER, 1002, ROLE, 1002), context -> game.peer(1).notice(new EchoBack("echo")));
        assertEquals("delivered echo on " + PLAYER + ":1002", next(handled));
    }

    @Test void aPeerMayHaveSeveralConnectionsAndARouteKeepsToOne() throws Exception {
        rank = startRank(0);
        game = startGame(rank.port(), 3, RpcNode.DEFAULT_TIMEOUT);
        await(() -> game.peer(1).connectionCount() == 3 && rank.peer(2).connectionCount() == 3);

        gameRuntime.execute(GameContext.of(PLAYER, 1001, ROLE, 1001), context -> {
            for (int i = 0; i < 50; i++) game.peer(1).notice(new Note("n" + i));
        });
        List<String> received = new ArrayList<>();
        for (int i = 0; i < 50; i++) received.add(next(handled));
        assertEquals(IntStream.range(0, 50).mapToObj(i -> "n" + i + " for 1001").toList(), received);
    }

    @Test void interceptorsSeeTheCallingNode() throws Exception {
        HandleInterceptor guard = new HandleInterceptor() {
            @Override public int before(HandleContext context, Method handler, Object message) {
                return ((RpcSource) context.source()).nodeId() == 2 && !(message instanceof Boom) ? SUCCESS : 9001;
            }
        };
        rank = RpcNode.builder(rankRuntime).nodeId(1).codec(CODEC).listen(new InetSocketAddress("127.0.0.1", 0))
                .intercept(guard).register(new RankService()).start();
        game = startGame(rank.port(), 1, RpcNode.DEFAULT_TIMEOUT);
        await(() -> game.peer(1).isConnected());
        callAsPlayer(new RankOf());
        assertEquals(PLAYER + ":1001 rank of 1001 asked by node 2 t1", next(answers));
        callAsPlayer(new Boom());
        assertEquals(PLAYER + ":1001 fail 9001", next(answers), "the interceptor's code reaches the caller");
    }

    @Test void failuresAtThePeerCarryItsCode() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        callAsPlayer(new Unknown());
        assertEquals(PLAYER + ":1001 fail " + HANDLER_NOT_FOUND, next(answers));
        callAsPlayer(new Boom());
        assertEquals(PLAYER + ":1001 fail " + RUNTIME_EXECUTION_ERROR, next(answers));
        callAsPlayer(new Unencodable());
        assertEquals(PLAYER + ":1001 fail " + RPC_HANDLER_ERROR, next(answers), "the peer cannot encode its result");
    }

    @Test void aCallNobodyAnswersTimesOut() throws Exception {
        connect(Duration.ofMillis(200));
        callAsPlayer(new Silent());
        assertEquals(PLAYER + ":1001 fail " + RPC_TIMEOUT, next(answers));
    }

    @Test void noticesActForTheSenderAndExpectNoAnswer() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        gameRuntime.execute(GameContext.of(PLAYER, 1001, ROLE, 1001), context -> game.peer(1).notice(new Note("hi")));
        assertEquals("hi for 1001", next(handled));
        game.peer(1).notice(new Note("outside a task"));
        assertEquals("outside a task for 0", next(handled));
        assertNull(answers.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test void aPeerIsUnavailableUntilItCanBeReached() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        game = startGame(port, 1, RpcNode.DEFAULT_TIMEOUT);
        callAsPlayer(new RankOf());
        assertEquals(PLAYER + ":1001 fail " + RPC_UNAVAILABLE, next(answers));
        assertFalse(game.peer(1).notice(new Note("lost")));

        rank = startRank(port);
        await(() -> game.peer(1).isConnected()); // the node keeps reconnecting
        callAsPlayer(new RankOf());
        assertEquals(PLAYER + ":1001 rank of 1001 asked by node 2 t1", next(answers));
    }

    @Test void aPeerAnsweringWithAnotherNodeIdIsNotJoined() throws Exception {
        rank = startRank(0);
        game = RpcNode.builder(gameRuntime).nodeId(2).codec(CODEC).peer(5, new InetSocketAddress("127.0.0.1", rank.port())).start();
        Thread.sleep(300);
        assertFalse(game.peer(5).isConnected(), "node 1 answered, not node 5");
        assertFalse(game.peer(1).isConnected());
    }

    @Test void aPeerThatGoesAwayFailsItsPendingCalls() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        callAsPlayer(new Silent());
        assertEquals("silent", next(handled));
        rank.close();
        assertEquals(PLAYER + ":1001 fail " + RPC_UNAVAILABLE, next(answers));
    }

    @Test void closingTheNodeFailsPendingAndLaterCalls() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        callAsPlayer(new Silent());
        assertEquals("silent", next(handled));
        game.close();
        assertEquals(PLAYER + ":1001 fail " + RPC_NODE_CLOSED, next(answers));
        callAsPlayer(new RankOf());
        assertEquals(PLAYER + ":1001 fail " + RPC_NODE_CLOSED, next(answers));
        assertFalse(game.peer(1).notice(new Note("late")));
    }

    @Test void misuseIsRejected() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        GameContext player = GameContext.of(PLAYER, 1001);
        assertSame(game.peer(9), game.peer(9));
        game.peer(9).call(player, new RankOf(), (c, r) -> {}, (c, errorCode) -> answers.add("peer 9 fail " + errorCode));
        assertEquals("peer 9 fail " + RPC_UNAVAILABLE, next(answers), "a peer that never connected");
        assertThrows(IllegalArgumentException.class, () -> game.peer(2), "this node");
        assertThrows(IllegalArgumentException.class, () -> game.peer(1).call(player, new Object(), (c, r) -> {}, null), "unencodable");
        assertThrows(IllegalStateException.class, () -> game.peer(1).call(new RankOf(), (c, r) -> {}), "no task to call from");

        assertThrows(IllegalStateException.class, () -> RpcNode.builder(gameRuntime).codec(CODEC).start(), "no node id");
        assertThrows(IllegalStateException.class, () -> RpcNode.builder(gameRuntime).nodeId(3).start(), "no codec");
        assertThrows(IllegalStateException.class, () -> RpcNode.builder(gameRuntime).nodeId(3).codec(CODEC).peer(3, NOWHERE).start());
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder(gameRuntime).peer(1, NOWHERE).peer(1, NOWHERE));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder(gameRuntime).peer(1, NOWHERE, 0));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder(rankRuntime).register(new RankService(), new RankService()));
    }
}
