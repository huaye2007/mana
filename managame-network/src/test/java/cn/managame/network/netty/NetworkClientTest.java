package cn.managame.network.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.netty.TestSupport.RecordingHandler;
import io.netty.bootstrap.Bootstrap;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
