package cn.managame.network.netty;

import cn.managame.network.connection.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.ssl.*;
import org.junit.jupiter.api.BeforeAll;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

abstract class NetworkTestSupport {
    static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
    static SslContext serverTls, clientTls;
    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        Path sockets = Path.of("target/socket-tmp").toAbsolutePath();
        Files.createDirectories(sockets);
        if (System.getProperty("os.name").startsWith("Windows")) {
            // JDK PipeImpl falls back to TCP when its AF_UNIX listener cannot bind.
            // This host intermittently fails AF_UNIX connect even with a valid long-form path.
            Path marker = sockets.resolve("tcp-pipe-only");
            Files.writeString(marker, "Test-only: force JDK Selector wakeup pipe to use TCP");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
        if (serverTls != null) return;
        Path store = Path.of("target/network-test.p12").toAbsolutePath();
        Files.deleteIfExists(store);
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "network", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "network-test", "-noprompt")
                .redirectErrorStream(true).redirectOutput(Path.of("target/keytool.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "network-test".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "network-test".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keys);
        serverTls = SslContextBuilder.forServer(kmf).sslProvider(SslProvider.JDK).build();
        clientTls = SslContextBuilder.forClient().trustManager(tmf).sslProvider(SslProvider.JDK).build();
    }
    static <T> T take(BlockingQueue<T> queue) throws Exception {
        T value = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(value, "Timed out waiting for network event");
        return value;
    }
    static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    static final Consumer<ChannelPipeline> INTS = p -> {
        p.addLast(new FixedLengthFrameDecoder(4));
        p.addLast(new MessageToMessageDecoder<ByteBuf>() {
            protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) { out.add(buffer.readInt()); }
        });
        p.addLast(new MessageToByteEncoder<Integer>() {
            protected void encode(ChannelHandlerContext ctx, Integer value, ByteBuf out) { out.writeInt(value); }
        });
    };
    static class Probe implements ConnectionHandler {
        final BlockingQueue<Connection> connected = new LinkedBlockingQueue<>();
        final BlockingQueue<Connection> disconnected = new LinkedBlockingQueue<>();
        final BlockingQueue<Object> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        final AtomicInteger connections = new AtomicInteger(), disconnects = new AtomicInteger();
        public void onConnected(Connection c) { connections.incrementAndGet(); connected.add(c); }
        public void onMessage(Connection c, Object m) {
            messages.add(m instanceof ByteBuf b ? b.toString(java.nio.charset.StandardCharsets.UTF_8) : m);
        }
        public void onDisconnected(Connection c) { disconnects.incrementAndGet(); disconnected.add(c); }
        public void onEvent(Connection c, Object event) { events.add(event); }
        public void onException(Connection c, Throwable cause) { errors.add(cause); }
    }
    static void shutdown(EventLoopGroup group) { group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly(); }
}