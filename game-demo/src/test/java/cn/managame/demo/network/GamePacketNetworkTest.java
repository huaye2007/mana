package cn.managame.demo.network;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkServer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GamePacketNetworkTest {
    @BeforeAll static void environment() throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Match game-network tests: avoid the host's intermittent AF_UNIX Selector pipe failure.
            Path marker = Path.of("target/packet-tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Test-only: use TCP for the Selector wakeup pipe");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    @Test void connectionCallbacksAndWritesUseGamePacketOverRealTcp() throws Exception {
        var response = new CompletableFuture<GamePacket>();
        var connections = new ConcurrentLinkedQueue<Connection>();
        var boss = new NioEventLoopGroup(1);
        var worker = new NioEventLoopGroup(1);
        ConnectionHandler echo = new Handler() {
            @Override public void onConnected(Connection connection) { connections.add(connection); }
            @Override public void onMessage(Connection connection, Object message) {
                GamePacket packet = (GamePacket) message;
                if (connection.write(packet) != WriteStatus.ACCEPTED) connection.close();
            }
        };
        ConnectionHandler receiver = new Handler() {
            @Override public void onMessage(Connection connection, Object message) {
                response.complete((GamePacket) message);
            }
            @Override public void onException(Connection connection, Throwable cause) {
                response.completeExceptionally(cause); connection.close();
            }
        };
        try (var server = NetworkServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0))
                .bossGroup(boss).workerGroup(worker).pipeline(GamePacketNetworkTest::codecs).handler(echo).build();
             var client = NetworkClient.builder().eventLoopGroup(worker)
                     .pipeline(GamePacketNetworkTest::codecs).handler(receiver).build()) {
            server.start();
            Connection connection = client.connect(server.localAddress());
            connections.add(connection);
            var request = GamePacketCodecTest.packet(1001, 42, -3, new byte[]{0, (byte) 0xff, 7});
            assertEquals(WriteStatus.ACCEPTED, connection.write(request));
            GamePacket reply = response.get(5, TimeUnit.SECONDS);
            assertEquals(1001, reply.getCommand());
            assertEquals(42, reply.getSeq());
            assertEquals(-3, reply.getCode());
            assertArrayEquals(new byte[]{0, (byte) 0xff, 7}, reply.getBody());
        } finally {
            connections.forEach(Connection::close);
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static void codecs(ChannelPipeline pipeline) {
        pipeline.addLast(new GamePacketDecoder(), new GamePacketEncoder());
    }

    private abstract static class Handler implements ConnectionHandler {
        @Override public void onConnected(Connection connection) {}
        @Override public void onDisconnected(Connection connection) {}
        @Override public void onException(Connection connection, Throwable cause) { connection.close(); }
    }
}
