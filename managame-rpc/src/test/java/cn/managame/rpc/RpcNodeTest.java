package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_NODE_CLOSED;
import static cn.managame.FrameworkErrorCodes.RPC_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Node 1 is a rank server that listens; node 2, a game server, connects to it and calls it. */
class RpcNodeTest {
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

    record RankOf(long playerId) implements Serializable {}
    record Later() implements Serializable {}
    record Refuse() implements Serializable {}
    record Boom() implements Serializable {}
    record Silent() implements Serializable {}
    record Unencodable() implements Serializable {}
    record Unknown() implements Serializable {}
    record Note(String text) implements Serializable {}
    record Probe() implements Serializable {}
    record EchoBack(String text) implements Serializable {}
    record Deliver(String text) implements Serializable {}

    /** What the handlers did, and what the callbacks got. */
    private final BlockingQueue<String> handled = new LinkedBlockingQueue<>(), answers = new LinkedBlockingQueue<>();
    private Duration idleTimeout = RpcNode.DEFAULT_IDLE_TIMEOUT;
    private RpcNode rank, game;

    /** Node 1's handler, as an application would write it: it decides by the message what to do. */
    private void rankHandler(RpcRequest request) {
        switch (request.message()) {
            case RankOf query -> request.reply("rank of " + query.playerId() + " asked by node " + request.peer().nodeId()
                    + " " + request.metadata().get(TRACE));
            case Later later -> Thread.ofVirtual().start(() -> request.reply("later"));
            case Refuse refuse -> request.fail(9001);
            case Boom boom -> throw new IllegalStateException("boom");
            case Silent silent -> handled.add("silent"); // and never answers
            case Unencodable unencodable -> request.reply(new Object());
            case Note note -> handled.add(note.text());
            case Probe probe -> handled.add("probe " + request.expectsAnswer() + " " + request.reply("x") + " " + request.fail(1));
            case EchoBack echo -> request.peer().notice(new Deliver(echo.text())); // calls the sender back
            default -> request.fail(HANDLER_NOT_FOUND);
        }
    }

    /** Node 2's handler. */
    private void gameHandler(RpcRequest request) {
        if (request.message() instanceof Deliver deliver) {
            handled.add("delivered " + deliver.text());
            request.reply("delivered " + deliver.text());
        } else {
            request.fail(HANDLER_NOT_FOUND);
        }
    }

    @AfterEach void stop() {
        if (game != null) game.close();
        if (rank != null) rank.close();
    }

    private RpcNode startRank(int port) {
        return RpcNode.builder().nodeId(1).codec(CODEC).listen(new InetSocketAddress("127.0.0.1", port))
                .idleTimeout(idleTimeout).handler(this::rankHandler).start();
    }

    private RpcNode startGame(int rankPort, int connections, Duration timeout) {
        return RpcNode.builder().nodeId(2).codec(CODEC).timeout(timeout).idleTimeout(idleTimeout)
                .peer(1, new InetSocketAddress("127.0.0.1", rankPort), connections)
                .handler(this::gameHandler).start();
    }

    private void connect(Duration timeout) throws InterruptedException {
        rank = startRank(0);
        game = startGame(rank.port(), 1, timeout);
        await(() -> game.peer(1).isConnected() && rank.peer(2).isConnected());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("The condition never held");
            Thread.sleep(10);
        }
    }

    private RpcCallback<Object> recorder() {
        return RpcCallback.of(result -> answers.add("ok " + result), errorCode -> answers.add("fail " + errorCode));
    }

    /** Calls node 1 with a trace in the metadata, keyed by player 1001. */
    private void call(Object request) {
        game.peer(1).call(request, Metadata.builder().put(TRACE, "t1").build(), 1001, recorder());
    }

    private static String next(BlockingQueue<String> queue) throws InterruptedException { return queue.poll(5, TimeUnit.SECONDS); }

    @Test void aCallIsAnsweredWithWhatTheHandlerReplied() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        call(new RankOf(1001));
        assertEquals("ok rank of 1001 asked by node 2 t1", next(answers));
        call(new Later());
        assertEquals("ok later", next(answers), "answered from another thread after the handler returned");
    }

    @Test void failuresCarryTheirCodes() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        call(new Refuse());
        assertEquals("fail 9001", next(answers));
        call(new Unknown());
        assertEquals("fail " + HANDLER_NOT_FOUND, next(answers));
        call(new Boom());
        assertEquals("fail " + RPC_HANDLER_ERROR, next(answers), "the handler threw");
        call(new Unencodable());
        assertEquals("fail " + RPC_HANDLER_ERROR, next(answers), "the result cannot be encoded");
    }

    @Test void aNodeWithoutAHandlerAnswersHandlerNotFound() throws Exception {
        rank = RpcNode.builder().nodeId(1).codec(CODEC).listen(new InetSocketAddress("127.0.0.1", 0)).start();
        game = startGame(rank.port(), 1, RpcNode.DEFAULT_TIMEOUT);
        await(() -> game.peer(1).isConnected());
        call(new RankOf(1));
        assertEquals("fail " + HANDLER_NOT_FOUND, next(answers));
    }

    @Test void aCallNobodyAnswersTimesOut() throws Exception {
        connect(Duration.ofMillis(200));
        call(new Silent());
        assertEquals("fail " + RPC_TIMEOUT, next(answers));
    }

    @Test void noticesExpectNoAnswer() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        assertTrue(game.peer(1).notice(new Note("hi")));
        assertEquals("hi", next(handled));
        game.peer(1).notice(new Probe());
        assertEquals("probe false false false", next(handled), "a notice cannot be answered");
        assertNull(answers.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test void callsGoBothWaysOverTheConnectionsAPeerOpened() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT); // node 2 does not listen
        rank.peer(2).call(new Deliver("mail"), recorder());
        assertEquals("delivered mail", next(handled));
        assertEquals("ok delivered mail", next(answers));

        game.peer(1).notice(new EchoBack("echo")); // node 1's handler calls back the peer the notice came from
        assertEquals("delivered echo", next(handled));
    }

    @Test void aPeerMayHaveSeveralConnectionsAndAKeyKeepsToOne() throws Exception {
        rank = startRank(0);
        game = startGame(rank.port(), 3, RpcNode.DEFAULT_TIMEOUT);
        await(() -> game.peer(1).connectionCount() == 3 && rank.peer(2).connectionCount() == 3);
        for (int i = 0; i < 50; i++) game.peer(1).notice(new Note("n" + i), Metadata.empty(), 1001);
        List<String> received = new ArrayList<>();
        for (int i = 0; i < 50; i++) received.add(next(handled));
        assertEquals(IntStream.range(0, 50).mapToObj(i -> "n" + i).toList(), received);
    }

    @Test void aCallbackThatThrowsDoesNotHurtTheNode() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        game.peer(1).call(new RankOf(1), RpcCallback.of(result -> { throw new IllegalStateException("callback"); }, errorCode -> {}));
        call(new RankOf(2));
        assertEquals("ok rank of 2 asked by node 2 t1", next(answers));
    }

    @Test void pingsKeepAnIdleConnectionUp() throws Exception {
        idleTimeout = Duration.ofMillis(300);
        connect(RpcNode.DEFAULT_TIMEOUT);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < end) { // a dropped connection would be down for the 100 ms before reconnecting
            assertTrue(game.peer(1).isConnected() && rank.peer(2).isConnected(), "the idle connection was dropped");
            Thread.sleep(10);
        }
        call(new RankOf(1001));
        assertEquals("ok rank of 1001 asked by node 2 t1", next(answers));
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
                                readHello(in);
                                writeHello(new DataOutputStream(connection.getOutputStream()), 1);
                                in.transferTo(OutputStream.nullOutputStream());
                            } catch (IOException gone) {}
                        });
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            game = startGame(silentNode.getLocalPort(), 1, Duration.ofSeconds(10));
            await(() -> game.peer(1).isConnected());
            call(new RankOf(1));
            assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "long before the call's 10-second timeout");
            await(() -> accepted.get() >= 2); // and the node connects again
        }
    }

    @Test void aConnectionThatNeverSaysHelloIsClosed() throws Exception {
        idleTimeout = Duration.ofMillis(300);
        rank = startRank(0);
        try (Socket silent = new Socket("127.0.0.1", rank.port())) {
            silent.setSoTimeout(5000);
            InputStream in = silent.getInputStream();
            while (in.read() != -1) {} // Pings, then the end of the stream; a timeout here fails the test
        }
    }

    @Test void aPingBeforeTheHelloIsAccepted() throws Exception {
        rank = startRank(0);
        try (Socket socket = new Socket("127.0.0.1", rank.port())) {
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(1);
            out.writeByte(4); // Ping
            writeHello(out, 2);
            assertEquals(1, readHello(new DataInputStream(socket.getInputStream())));
            await(() -> rank.peer(2).isConnected());
        }
    }

    /** A Hello frame as RpcFrameCodec lays it out, inside the length framing. */
    private static void writeHello(DataOutputStream out, int nodeId) throws IOException {
        out.writeInt(5);
        out.writeByte(0);
        out.writeInt(nodeId);
        out.flush();
    }

    /** Reads frames up to a Hello, skipping Pings, and returns its node id. */
    private static int readHello(DataInputStream in) throws IOException {
        while (true) {
            byte[] frame = new byte[in.readInt()];
            in.readFully(frame);
            if (frame[0] == 0) return ByteBuffer.wrap(frame, 1, 4).getInt();
        }
    }

    @Test void aPeerIsUnavailableUntilItCanBeReached() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        game = startGame(port, 1, RpcNode.DEFAULT_TIMEOUT);
        call(new RankOf(1));
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
        assertFalse(game.peer(1).notice(new Note("lost")));

        rank = startRank(port);
        await(() -> game.peer(1).isConnected()); // the node keeps reconnecting
        call(new RankOf(1));
        assertEquals("ok rank of 1 asked by node 2 t1", next(answers));
    }

    @Test void aPeerAnsweringWithAnotherNodeIdIsNotJoined() throws Exception {
        rank = startRank(0);
        game = RpcNode.builder().nodeId(2).codec(CODEC).peer(5, new InetSocketAddress("127.0.0.1", rank.port())).start();
        Thread.sleep(300);
        assertFalse(game.peer(5).isConnected(), "node 1 answered, not node 5");
        assertFalse(game.peer(1).isConnected());
    }

    @Test void aPeerThatGoesAwayFailsItsPendingCalls() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        call(new Silent());
        assertEquals("silent", next(handled));
        rank.close();
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers));
    }

    @Test void closingTheNodeFailsPendingAndLaterCalls() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        call(new Silent());
        assertEquals("silent", next(handled));
        game.close();
        assertEquals("fail " + RPC_NODE_CLOSED, next(answers));
        call(new RankOf(1));
        assertEquals("fail " + RPC_NODE_CLOSED, next(answers));
        assertFalse(game.peer(1).notice(new Note("late")));
    }

    @Test void misuseIsRejected() throws Exception {
        connect(RpcNode.DEFAULT_TIMEOUT);
        assertSame(game.peer(9), game.peer(9));
        game.peer(9).call(new RankOf(1), recorder());
        assertEquals("fail " + RPC_UNAVAILABLE, next(answers), "a peer that never connected");
        assertThrows(IllegalArgumentException.class, () -> game.peer(2), "this node");
        assertThrows(IllegalArgumentException.class, () -> game.peer(1).call(new Object(), recorder()), "unencodable");

        assertThrows(IllegalStateException.class, () -> RpcNode.builder().codec(CODEC).start(), "no node id");
        assertThrows(IllegalStateException.class, () -> RpcNode.builder().nodeId(3).start(), "no codec");
        assertThrows(IllegalStateException.class, () -> RpcNode.builder().nodeId(3).codec(CODEC).peer(3, NOWHERE).start());
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().peer(1, NOWHERE).peer(1, NOWHERE));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().peer(1, NOWHERE, 0));
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().idleTimeout(Duration.ofMillis(2)));
    }
}
