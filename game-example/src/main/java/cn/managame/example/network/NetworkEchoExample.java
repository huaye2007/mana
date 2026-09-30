package cn.managame.example.network;

import cn.managame.network.connection.*;
import cn.managame.network.netty.*;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.*;
import io.netty.handler.codec.string.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** A complete framed TCP example with explicit resource ownership. */
public final class NetworkEchoExample {
    private NetworkEchoExample() {}
    public static void main(String[] args) throws Exception { System.out.println(roundTrip("hello game-network")); }

    public static String roundTrip(String message) throws Exception {
        CompletableFuture<String> response = new CompletableFuture<>();
        ConnectionHandler echo = new Handler() {
            public void onMessage(Connection connection, Object message) {
                if (connection.write(message) != WriteStatus.ACCEPTED) connection.close();
            }
        };
        ConnectionHandler receiver = new Handler() {
            public void onMessage(Connection connection, Object message) { response.complete((String) message); }
            public void onException(Connection connection, Throwable cause) {
                response.completeExceptionally(cause); connection.close();
            }
            public void onDisconnected(Connection connection) {
                response.completeExceptionally(new IllegalStateException("Disconnected before response"));
            }
        };
        try (NetworkServer server = NetworkServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0))
                .pipeline(NetworkEchoExample::codec).handler(echo).build();
             NetworkClient client = NetworkClient.builder().pipeline(NetworkEchoExample::codec).handler(receiver).build()) {
            server.start();
            Connection connection = client.connect(server.localAddress());
            try {
                if (connection.write(message) != WriteStatus.ACCEPTED) throw new IllegalStateException("Write rejected");
                return response.get(5, TimeUnit.SECONDS);
            } finally { connection.close(); }
        }
    }
    private static void codec(ChannelPipeline pipeline) {
        pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));
        pipeline.addLast(new LengthFieldPrepender(4));
        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
    }
    private abstract static class Handler implements ConnectionHandler {
        public void onConnected(Connection connection) {}
        public void onDisconnected(Connection connection) {}
        public void onException(Connection connection, Throwable cause) { connection.close(); }
    }
}