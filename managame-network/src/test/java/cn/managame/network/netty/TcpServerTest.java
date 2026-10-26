package cn.managame.network.netty;

import static cn.managame.network.netty.TestSupport.STRING_CODEC;
import static cn.managame.network.netty.TestSupport.serve;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.error.NetworkException;
import cn.managame.network.netty.TestSupport.RecordingHandler;
import cn.managame.network.netty.TestSupport.TestClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TcpServerTest {
    private static final HandlerChain FRAMING = NetworkHandlers.lengthFieldFraming(1024);

    private static ConnectionInitializer.Builder strings(RecordingHandler handler) {
        return ConnectionInitializer.tcp().addLast(FRAMING).addLast(STRING_CODEC).handler(handler);
    }

    private static TestClient stringClient(NetworkServer server) {
        // The client reuses the very chains the server was built from.
        return new TestClient(server.port(), p -> { FRAMING.addTo(p); STRING_CODEC.addTo(p); });
    }

    @Test void echoesFramedMessagesWithLifecycleInOrder() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(strings(handler))) {
            try (TestClient client = stringClient(server)) {
                assertEquals("active", handler.next());
                client.send("hello");
                client.send("world");
                assertEquals("read:hello", handler.next());
                assertEquals("read:world", handler.next());
                assertEquals("echo:hello", client.next());
                assertEquals("echo:world", client.next());
            }
            assertEquals("inactive", handler.next());
        }
    }

    @Test void closeWithOwnEventLoopsDisconnectsBeforeReturning() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        NetworkServer server = serve(strings(handler));
        try (TestClient client = stringClient(server)) {
            assertEquals("active", handler.next());
            Connection connection = handler.connections.take();
            server.close();
            assertEquals("inactive", handler.events.poll(), "server-owned EventLoops drain onInActive before close returns");
            assertTrue(client.awaitClosed());
            assertEquals(WriteStatus.INACTIVE, connection.write("late"));
            assertFalse(server.isActive());
            server.close();
        }
    }

    @Test void closeLeavesConnectionsOnApplicationEventLoopsToTheApplication() throws Exception {
        EventLoopGroup boss = NetworkTransports.newEventLoopGroup(1, "app-boss");
        EventLoopGroup worker = NetworkTransports.newEventLoopGroup(1, "app-worker");
        RecordingHandler handler = new RecordingHandler();
        try {
            NetworkServer server = serve(boss, worker, strings(handler));
            try (TestClient client = stringClient(server)) {
                assertEquals("active", handler.next());
                Connection connection = handler.connections.take();
                server.close();
                assertFalse(server.isActive());
                assertFalse(boss.isShuttingDown() || worker.isShuttingDown(), "the application's EventLoops keep running");
                assertNull(handler.poll(200), "the server keeps no connections, so it closes none");

                client.send("still here");
                assertEquals("read:still here", handler.next());
                assertEquals("echo:still here", client.next());

                connection.close(); // the application closes what it owns
                assertEquals("inactive", handler.next());
                assertTrue(client.awaitClosed());
            }
        } finally {
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test void bootstrapOptionsApplyWhileTheServerOwnsTheEventLoopsItCreates() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        WriteBufferWaterMark waterMark = new WriteBufferWaterMark(8 * 1024, 16 * 1024);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
                .childHandler(strings(handler).build());
        NetworkServer server = NetworkServer.start(bootstrap, TestSupport.LOCAL);
        try (TestClient client = stringClient(server)) {
            assertEquals("active", handler.next());
            Channel accepted = ((NettyConnection) handler.connections.take()).channel();
            assertSame(waterMark, accepted.config().getOption(ChannelOption.WRITE_BUFFER_WATER_MARK));
            assertEquals(Boolean.TRUE, accepted.config().getOption(ChannelOption.TCP_NODELAY), "TCP_NODELAY defaults to true");
            assertNull(bootstrap.config().group(), "the application's bootstrap is left unchanged");

            server.close();
            assertEquals("inactive", handler.events.poll(), "EventLoops the server created are shut down with it");
            assertTrue(client.awaitClosed());
        }
    }

    @Test void anExplicitTcpNoDelayWins() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = NetworkServer.start(new ServerBootstrap()
                .childOption(ChannelOption.TCP_NODELAY, false)
                .childHandler(ConnectionInitializer.tcp().handler(handler).build()), TestSupport.LOCAL);
             TestClient client = new TestClient(server.port(), p -> {})) {
            assertEquals("active", handler.next());
            Channel accepted = ((NettyConnection) handler.connections.take()).channel();
            assertEquals(Boolean.FALSE, accepted.config().getOption(ChannelOption.TCP_NODELAY));
        }
    }

    @Test void handlerExceptionsAreReportedAndTheConnectionStaysOpen() throws Exception {
        RecordingHandler handler = new RecordingHandler() {
            @Override public void onRead(Connection connection, Object message) {
                if ("boom".equals(message)) throw new IllegalStateException("boom");
                super.onRead(connection, message);
            }
        };
        try (NetworkServer server = serve(strings(handler)); TestClient client = stringClient(server)) {
            assertEquals("active", handler.next());
            client.send("boom");
            assertEquals("exception:IllegalStateException", handler.next());
            client.send("ok");
            assertEquals("read:ok", handler.next());
            assertEquals("echo:ok", client.next());
        }
    }

    @Test void readTimeoutClosesSilentConnections() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.tcp()
                .addLast(NetworkHandlers.readTimeout(Duration.ofMillis(200))).handler(handler));
             TestClient client = new TestClient(server.port(), p -> {})) {
            assertEquals("active", handler.next());
            assertEquals("exception:ReadTimeoutException", handler.next());
            assertEquals("inactive", handler.next());
            assertTrue(client.awaitClosed());
        }
    }

    @Test void bindFailureThrowsNetworkException() {
        RecordingHandler handler = new RecordingHandler();
        ConnectionInitializer initializer = strings(handler).build();
        try (NetworkServer first = NetworkServer.start(TestSupport.LOCAL, initializer)) {
            InetSocketAddress taken = first.localAddress();
            assertThrows(NetworkException.class, () -> NetworkServer.start(taken, initializer));
            assertTrue(first.isActive(), "one initializer serves any number of listeners; the failed one leaves it alone");
        }
    }
}
