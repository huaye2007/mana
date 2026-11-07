package cn.managame.network.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.connection.Connection;
import cn.managame.network.netty.TestSupport.RecordingHandler;
import io.netty.bootstrap.Bootstrap;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class NetworkClientTest {
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void eventually(BooleanSupplier condition, String message) throws InterruptedException {
        for (int i = 0; i < 250 && !condition.getAsBoolean(); i++) TimeUnit.MILLISECONDS.sleep(20);
        assertTrue(condition.getAsBoolean(), message);
    }

    @Test void reconnectsUntilTheServerIsUpAndAfterTheConnectionDrops() throws Exception {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", freePort());
        RecordingHandler client = new RecordingHandler(), server = new RecordingHandler();
        NetworkClient connector = NetworkClient.connect(new Bootstrap().handler(ConnectionInitializer.tcp().handler(client).build()),
                address, Duration.ofMillis(20), Duration.ofMillis(100));
        try {
            TimeUnit.MILLISECONDS.sleep(150);
            assertFalse(connector.isConnected(), "nothing listens yet; the client keeps trying");

            try (NetworkServer listener = NetworkServer.start(address, ConnectionInitializer.tcp().handler(server).build())) {
                assertEquals("active", client.next(), "connected once the server is up");
                eventually(connector::isConnected, "reported as connected");

                assertEquals("active", server.next());
                server.connections.take().close();
                assertEquals("inactive", client.next());
                assertEquals("active", client.next(), "reconnected after the server dropped the connection");

                connector.close();
                assertEquals("inactive", client.next());
                assertNull(client.poll(300), "a closed client does not reconnect");
                assertFalse(connector.isConnected());
            }
        } finally {
            connector.close();
        }
    }

    @Test void aConnectionClosedAtOnceWaitsLongerEachTime() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        RecordingHandler refusing = new RecordingHandler() {   // as a server refusing a handshake does
            @Override public void onActive(Connection connection) {
                accepted.incrementAndGet();
                connection.close();
            }
        };
        try (NetworkServer server = NetworkServer.start(TestSupport.LOCAL, ConnectionInitializer.tcp().handler(refusing).build());
             NetworkClient client = NetworkClient.connect(new Bootstrap().handler(ConnectionInitializer.tcp().handler(new RecordingHandler()).build()),
                     server.localAddress(), Duration.ofMillis(10), Duration.ofSeconds(10))) {
            TimeUnit.MILLISECONDS.sleep(1000);
        }
        assertTrue(accepted.get() >= 2 && accepted.get() <= 10, "10, 20, 40 ms and so on come to 7 in a second; saw " + accepted);
    }

    @Test void aConnectionThatStayedUpStartsOverFromTheShortestWait() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        RecordingHandler server = new RecordingHandler() {
            @Override public void onActive(Connection connection) {
                if (accepted.incrementAndGet() <= 5) connection.close();   // the waits grow to the longest
                else super.onActive(connection);
            }
        };
        try (NetworkServer listener = NetworkServer.start(TestSupport.LOCAL, ConnectionInitializer.tcp().handler(server).build());
             NetworkClient client = NetworkClient.connect(new Bootstrap().handler(ConnectionInitializer.tcp().handler(new RecordingHandler()).build()),
                     listener.localAddress(), Duration.ofMillis(20), Duration.ofMillis(400))) {
            Connection steady = server.connections.poll(5, TimeUnit.SECONDS);
            assertNotNull(steady);
            TimeUnit.MILLISECONDS.sleep(450);
            long closed = System.nanoTime();
            steady.close();
            assertNotNull(server.connections.poll(5, TimeUnit.SECONDS), "connected again");
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closed);
            assertTrue(waited < 200, "20 ms rather than 400; waited " + waited);
        }
    }

    @Test void closingAClientThatNeverConnectedStopsIt() throws Exception {
        RecordingHandler client = new RecordingHandler();
        NetworkClient connector = NetworkClient.connect(new InetSocketAddress("127.0.0.1", freePort()),
                ConnectionInitializer.tcp().handler(client).build());
        TimeUnit.MILLISECONDS.sleep(100);
        connector.close();
        assertFalse(connector.isConnected());
        assertNull(client.poll(200));
    }
}
