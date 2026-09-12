package cn.managame.network.tests;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;
import cn.managame.network.netty.*;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.*;
import io.netty.util.ReferenceCountUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.net.ssl.KeyManagerFactory;

/** Explicit opt-in: -Dtest=NetworkLoadIT. One JVM hosts both load generator and server. */
class NetworkLoadIT {
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void capacitySoakAndConnectionChurn() throws Exception {
        String protocol = System.getProperty("network.load.protocol", "tcp");
        assertTrue(Set.of("tcp", "ws", "wss").contains(protocol));
        int count = Integer.getInteger("network.load.connections", 10000);
        int seconds = Integer.getInteger("network.load.seconds", 60);
        assertTrue(count > 0 && count <= 20000);
        assertTrue(seconds > 0 && seconds <= 1200);
        boolean websocket = !protocol.equals("tcp");
        var connections = new Connection[count];
        var round = new AtomicReference<Round>();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var metrics = new ArrayList<String>();
        var latencies = new ArrayList<long[]>();
        var serverConnections = new AtomicInteger();
        var serverReady = new CountDownLatch(count);
        long started = System.nanoTime();
        long beforeFd = openFiles();
        var resources =
                NetworkResources.builder()
                        .ioThreads(4)
                        .httpThreads(1)
                        .diagnostics((m, t) -> failures.add(t))
                        .build();
        NetworkHandler serverHandler =
                new NetworkHandler() {
                    public void onConnected(Connection c) {
                        serverConnections.incrementAndGet();
                        serverReady.countDown();
                    }

                    public void onDisconnected(Connection c) {
                        serverConnections.decrementAndGet();
                    }

                    public void onMessage(Connection c, Object message) {
                        ReferenceCountUtil.retain(message);
                        if (!c.write(message)) ReferenceCountUtil.release(message);
                    }
                };
        NetworkHandler clientHandler =
                (c, m) -> {
                    ByteBuf payload =
                            m instanceof WebSocketFrame frame ? frame.content() : (ByteBuf) m;
                    int index = payload.readInt();
                    int sequence = payload.readInt();
                    long sent = payload.readLong();
                    Round current = round.get();
                    if (current == null
                            || sequence != current.sequence
                            || index < 0
                            || index >= count
                            || current.received.getAndIncrement(index) != 0) {
                        failures.add(new AssertionError("Duplicate or out-of-round message"));
                        return;
                    }
                    current.latency[index] = System.nanoTime() - sent;
                    current.done.countDown();
                };
        var tcpServer =
                TcpNetworkServer.builder()
                        .resources(resources)
                        .listen("127.0.0.1", 0)
                        .option(ChannelOption.SO_BACKLOG, 4096)
                        .handlerFactory(() -> serverHandler)
                        .pipeline((c, p) -> framing(p))
                        .build();
        var tcpClient =
                TcpNetworkClient.builder()
                        .resources(resources)
                        .handlerFactory(() -> clientHandler)
                        .pipeline((c, p) -> framing(p))
                        .build();
        var wsServerBuilder =
                WsNetworkServer.builder()
                        .resources(resources)
                        .listen("127.0.0.1", 0)
                        .option(ChannelOption.SO_BACKLOG, 4096)
                        .handlerFactory(() -> serverHandler);
        var wsClientBuilder =
                WsNetworkClient.builder().resources(resources).handlerFactory(() -> clientHandler);
        if (protocol.equals("wss")) {
            var store = KeyStore.getInstance("PKCS12");
            try (var input = getClass().getResourceAsStream("/localhost-test.p12")) {
                store.load(input, "changeit".toCharArray());
            }
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, "changeit".toCharArray());
            wsServerBuilder.sslContext(
                    SslContextBuilder.forServer(kmf).sslProvider(SslProvider.JDK).build());
            wsClientBuilder.sslContext(
                    SslContextBuilder.forClient()
                            .sslProvider(SslProvider.JDK)
                            .trustManager((X509Certificate) store.getCertificate("localhost"))
                            .build());
        }
        var wsServer = wsServerBuilder.build();
        var wsClient = wsClientBuilder.build();
        NetworkServer server = websocket ? wsServer : tcpServer;
        NetworkClient client = websocket ? wsClient : tcpClient;
        try {
            server.start();
            client.init();
            int port =
                    (websocket ? wsServer.boundAddresses() : tcpServer.boundAddresses())
                            .values()
                            .iterator()
                            .next()
                            .getPort();
            var uri = URI.create(protocol + "://localhost:" + port + "/");
            long connectStart = System.nanoTime();
            connectBatch(connections, 0, count, websocket, uri, port, tcpClient, wsClient);
            assertTrue(serverReady.await(30, TimeUnit.SECONDS), "Server acceptance stalled");
            long connectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStart);
            assertEquals(count, serverConnections.get());
            ByteBufAllocator allocator = NettyAccess.channel(connections[0]).alloc();
            metrics.add(
                    snapshot(
                            "connected",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            allocator));
            System.out.printf(
                    "LOAD %s connected=%d connectMs=%d%n", protocol, count, connectMillis);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            int sequence = 0;
            while (System.nanoTime() < until) {
                long roundStart = System.nanoTime();
                var current = new Round(++sequence, count);
                round.set(current);
                for (int i = 0; i < count; i++) {
                    ByteBuf payload =
                            Unpooled.buffer(16)
                                    .writeInt(i)
                                    .writeInt(sequence)
                                    .writeLong(System.nanoTime());
                    Object message = websocket ? new BinaryWebSocketFrame(payload) : payload;
                    if (!connections[i].write(message)) {
                        ReferenceCountUtil.release(message);
                        fail("Connection unexpectedly inactive");
                    }
                }
                assertTrue(current.done.await(30, TimeUnit.SECONDS), "Echo round stalled");
                assertTrue(failures.isEmpty(), () -> "Network errors: " + failures);
                Arrays.sort(current.latency);
                latencies.add(current.latency);
                metrics.add(
                        snapshot(
                                "round-" + sequence,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                                allocator));
                if (sequence % 10 == 0)
                    System.out.printf(
                            "LOAD %s round=%d p99Micros=%d%n",
                            protocol, sequence, current.latency[(int) (count * .99)] / 1000);
                long remaining = TimeUnit.SECONDS.toNanos(1) - (System.nanoTime() - roundStart);
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
            }
            // Abrupt TCP resets on a quarter of connections, then reconnect and echo on all.
            int churn = Math.max(1, count / 4);
            for (int i = 0; i < churn; i++) {
                Channel ch = NettyAccess.channel(connections[i]);
                ch.config().setOption(ChannelOption.SO_LINGER, 0);
                ch.close().sync();
            }
            connectBatch(connections, 0, churn, websocket, uri, port, tcpClient, wsClient);
            var last = new Round(++sequence, count);
            round.set(last);
            for (int i = 0; i < count; i++) {
                ByteBuf payload =
                        Unpooled.buffer(16)
                                .writeInt(i)
                                .writeInt(sequence)
                                .writeLong(System.nanoTime());
                Object message = websocket ? new BinaryWebSocketFrame(payload) : payload;
                if (!connections[i].write(message)) {
                    ReferenceCountUtil.release(message);
                    fail("Post-churn write rejected");
                }
            }
            assertTrue(last.done.await(30, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), () -> "Network errors: " + failures);
            Arrays.sort(last.latency);
            latencies.add(last.latency);
            metrics.add(
                    snapshot(
                            "churn-complete",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            allocator));
            long[] all = latencies.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            String result =
                    "{\n\"protocol\":\""
                            + protocol
                            + "\",\"connections\":"
                            + count
                            + ",\"requestedSeconds\":"
                            + seconds
                            + ",\"rounds\":"
                            + sequence
                            + ",\"echoes\":"
                            + all.length
                            + ",\"reconnected\":"
                            + churn
                            + ",\"connectMillis\":"
                            + connectMillis
                            + ",\"p50Micros\":"
                            + all[all.length / 2] / 1000
                            + ",\"p99Micros\":"
                            + all[(int) (all.length * .99)] / 1000
                            + ",\"os\":\""
                            + System.getProperty("os.name")
                            + "\",\"jdk\":\""
                            + System.getProperty("java.version")
                            + "\",\"allocator\":\""
                            + allocator.getClass().getSimpleName()
                            + "\",\"errors\":0";
            client.destroy();
            server.stop();
            resources.close();
            metrics.add(
                    snapshot(
                            "closed",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                            allocator));
            long afterFd = openFiles();
            if (beforeFd >= 0)
                assertTrue(
                        afterFd <= beforeFd + 4, "File descriptors did not return near baseline");
            assertEquals(0, serverConnections.get());
            result +=
                    ",\"fdBefore\":"
                            + beforeFd
                            + ",\"fdAfter\":"
                            + afterFd
                            + ",\"samples\":["
                            + String.join(",", metrics)
                            + "]\n}";
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/load-" + protocol + ".json"), result);
            System.out.printf(
                    "LOAD %s PASS echoes=%d p99Micros=%d fd=%d->%d%n",
                    protocol, all.length, all[(int) (all.length * .99)] / 1000, beforeFd, afterFd);
        } finally {
            try {
                client.destroy();
            } finally {
                try {
                    server.stop();
                } finally {
                    resources.close();
                }
            }
        }
    }

    private static void connectBatch(
            Connection[] connections,
            int from,
            int to,
            boolean websocket,
            URI uri,
            int port,
            TcpNetworkClient tcp,
            WsNetworkClient ws)
            throws Exception {
        // Bound only the test driver's simultaneous handshakes, not the component.
        for (int start = from; start < to; start += 256) {
            var results = new ArrayList<CompletableFuture<Connection>>();
            for (int i = start; i < Math.min(to, start + 256); i++) {
                var result = new CompletableFuture<Connection>();
                results.add(result);
                ConnectCallback callback =
                        new ConnectCallback() {
                            public void onSuccess(Connection c) {
                                result.complete(c);
                            }

                            public void onFailure(Throwable t) {
                                result.completeExceptionally(t);
                            }
                        };
                if (websocket) ws.connect(uri, callback);
                else tcp.connect("127.0.0.1", port, callback);
            }
            for (int i = 0; i < results.size(); i++)
                connections[start + i] = results.get(i).get(30, TimeUnit.SECONDS);
        }
    }

    private static void framing(ChannelPipeline p) {
        p.addLast(new LengthFieldBasedFrameDecoder(64, 0, 4, 0, 4), new LengthFieldPrepender(4));
    }

    private static long openFiles() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.UnixOperatingSystemMXBean unix
                ? unix.getOpenFileDescriptorCount()
                : -1;
    }

    private static String snapshot(String phase, long elapsedMillis, ByteBufAllocator allocator) {
        ByteBufAllocatorMetric allocation =
                allocator instanceof ByteBufAllocatorMetricProvider provider
                        ? provider.metric()
                        : null;
        long cpu =
                ((com.sun.management.OperatingSystemMXBean)
                                ManagementFactory.getOperatingSystemMXBean())
                        .getProcessCpuTime();
        long direct =
                ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                        .filter(p -> p.getName().equals("direct"))
                        .mapToLong(BufferPoolMXBean::getMemoryUsed)
                        .sum();
        return "{\"phase\":\""
                + phase
                + "\",\"elapsedMillis\":"
                + elapsedMillis
                + ",\"heapBytes\":"
                + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                + ",\"directBufferBytes\":"
                + direct
                + ",\"allocatorDirectBytes\":"
                + (allocation == null ? -1 : allocation.usedDirectMemory())
                + ",\"allocatorHeapBytes\":"
                + (allocation == null ? -1 : allocation.usedHeapMemory())
                + ",\"threads\":"
                + ManagementFactory.getThreadMXBean().getThreadCount()
                + ",\"openFiles\":"
                + openFiles()
                + ",\"processCpuNanos\":"
                + cpu
                + "}";
    }

    private static final class Round {
        final int sequence;
        final CountDownLatch done;
        final AtomicIntegerArray received;
        final long[] latency;

        Round(int sequence, int count) {
            this.sequence = sequence;
            done = new CountDownLatch(count);
            received = new AtomicIntegerArray(count);
            latency = new long[count];
        }
    }
}
