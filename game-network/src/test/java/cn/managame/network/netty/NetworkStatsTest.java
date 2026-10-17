package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.NetworkStats;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.connector.ConnectCallback;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkStatsTest extends NetworkTestSupport {
    private static void await(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(ready.getAsBoolean(), "condition did not become true");
    }
    private static Connection connect(NetworkClient client, InetSocketAddress address) throws Exception {
        Promise<Connection> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        client.connectAsync(address, new ConnectCallback() {
            public void onSuccess(Connection connection) { result.trySuccess(connection); }
            public void onFailure(Throwable cause) { result.tryFailure(cause); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }

    @Test void aListenerAndAClientCountEstablishmentTrafficAndClosure() throws Exception {
        Probe serverProbe = new Probe(), clientProbe = new Probe();
        try (NetworkServer server = NetworkServer.builder().bindAddress(LOCAL).handler(serverProbe).pipeline(INTS).build();
             NetworkClient client = NetworkClient.builder().handler(clientProbe).pipeline(INTS).build()) {
            assertEquals(new NetworkStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), server.stats());
            server.start();
            InetSocketAddress address = (InetSocketAddress) server.localAddress();

            Connection outbound = connect(client, address);
            Connection inbound = serverProbe.connected.poll(5, TimeUnit.SECONDS);
            assertNotNull(inbound);
            await(() -> server.stats().established() == 1);
            NetworkStats accepting = server.stats();
            assertEquals(1, accepting.accepted());
            assertEquals(1, accepting.liveConnections());
            assertEquals(0, accepting.closed());
            assertEquals(0, accepting.establishmentFailures());
            // A client never accepts, so that count stays 0 while its own establishment is counted.
            assertEquals(0, client.stats().accepted());
            assertEquals(1, client.stats().established());
            assertEquals(1, client.stats().liveConnections());

            assertEquals(WriteStatus.ACCEPTED, outbound.write(7));
            assertEquals(7, serverProbe.messages.poll(5, TimeUnit.SECONDS));
            await(() -> server.stats().messagesRead() == 1);
            assertEquals(1, client.stats().writesAccepted());
            assertEquals(0, client.stats().writesNotWritable());

            outbound.close();
            await(() -> server.stats().closed() == 1 && client.stats().closed() == 1);
            assertEquals(0, server.stats().liveConnections());
            assertEquals(0, client.stats().liveConnections());
            // A write to a connection that is gone is refused, and the refusal is counted, not silent.
            assertEquals(WriteStatus.INACTIVE, outbound.write(8));
            assertEquals(1, client.stats().writesInactive());
            assertEquals(1, client.stats().writesAccepted());
        }
    }
    @Test void aChannelThatNeverReachesTheApplicationCountsAsAnEstablishmentFailure() throws Exception {
        Probe probe = new Probe();
        try (NetworkServer server = NetworkServer.builder().bindAddress(LOCAL).handler(probe)
                // A pipeline configurer that throws fails establishment after the channel was accepted.
                .pipeline(p -> { throw new IllegalStateException("pipeline rejected"); }).build()) {
            server.start();
            InetSocketAddress address = (InetSocketAddress) server.localAddress();
            try (NetworkClient client = NetworkClient.builder().handler(new Probe()).build()) {
                try { connect(client, address); } catch (Exception expected) { /* the server closes it */ }
                await(() -> server.stats().establishmentFailures() == 1);
                NetworkStats stats = server.stats();
                assertEquals(1, stats.accepted());
                assertEquals(0, stats.established());
                assertEquals(0, stats.closed());
                assertEquals(0, stats.liveConnections());
                assertTrue(probe.connected.isEmpty());
            }
        }
    }
}
