package cn.managame.example;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkHandlers;
import com.google.protobuf.Message;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A game client on managame-network, for {@link ExampleClient}, the test, or robots that load a server: a
 * {@link NetworkClient} keeps the connection up, the same framing and {@link PacketCodec} as the server's turn frames
 * into packets, and each answer completes the future of its request, found by seq. The client keeps its own connection,
 * as the network layer keeps none. After a reconnect the server knows the client no more, so a real client logs in
 * again; requests waiting when the connection dropped fail.
 */
final class GameClient implements ConnectionHandler, AutoCloseable {
    private final Map<Integer, CompletableFuture<Packet>> waiting = new ConcurrentHashMap<>();
    private final AtomicInteger lastSeq = new AtomicInteger();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final NetworkClient client;
    private volatile Connection connection;

    /** Connects to the game server, waiting up to 5 seconds for the first connection. */
    GameClient(InetSocketAddress server) throws InterruptedException {
        client = NetworkClient.connect(server, ConnectionInitializer.tcp()
                .addLast(NetworkHandlers.lengthFieldFraming(64 * 1024))
                .addLast("packet-codec", PacketCodec.INSTANCE)
                .handler(this)
                .build());
        if (!connected.await(5, TimeUnit.SECONDS)) {
            client.close();
            throw new IllegalStateException("Cannot reach the game server at " + server);
        }
    }

    /**
     * Sends {@code message} under a new seq; the future completes with the answer, an error code included, on the
     * connection's EventLoop, so what follows it must not block. It fails with IOException when there is no connection
     * or it drops before the answer.
     */
    CompletableFuture<Packet> request(Message message) {
        Connection current = connection;
        if (current == null) return CompletableFuture.failedFuture(new IOException("Not connected"));
        int seq = lastSeq.incrementAndGet();
        CompletableFuture<Packet> answer = new CompletableFuture<>();
        waiting.put(seq, answer);
        if (current.write(Packet.request(seq, message)) != WriteStatus.ACCEPTED && waiting.remove(seq, answer))
            answer.completeExceptionally(new IOException("The connection cannot take the request"));
        return answer;
    }

    /** {@link #request} and wait, up to 5 seconds, for the answer. */
    Packet call(Message message) throws Exception { return request(message).get(5, TimeUnit.SECONDS); }

    @Override public void onActive(Connection connection) {
        this.connection = connection;
        connected.countDown();
    }

    @Override public void onRead(Connection connection, Object message) {
        Packet answer = (Packet) message;
        CompletableFuture<Packet> request = waiting.remove(answer.seq());
        if (request != null) request.complete(answer);
    }

    @Override public void onInActive(Connection connection) {
        this.connection = null;
        for (Integer seq : waiting.keySet()) {
            CompletableFuture<Packet> request = waiting.remove(seq);
            if (request != null) request.completeExceptionally(new IOException("The connection dropped"));
        }
    }

    @Override public void onException(Connection connection, Throwable cause) { connection.close(); }

    /** Stops reconnecting and closes the connection; not from a future's callback, which runs on the client's EventLoop. */
    @Override public void close() { client.close(); }
}
