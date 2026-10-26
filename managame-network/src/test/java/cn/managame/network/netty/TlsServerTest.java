package cn.managame.network.netty;

import static cn.managame.network.netty.TestSupport.serve;
import static cn.managame.network.netty.TestSupport.STRING_CODEC;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.error.NetworkException;
import cn.managame.network.netty.TestSupport.RecordingHandler;
import cn.managame.network.netty.TestSupport.TestClient;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.ssl.SslContext;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsServerTest {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static SslContext serverSsl, clientSsl;

    @BeforeAll static void certificates(@TempDir Path directory) throws Exception {
        serverSsl = NetworkSsl.server(TestSupport.selfSignedKeyStore(directory, PASSWORD), PASSWORD);
        clientSsl = TestSupport.insecureClientSsl();
    }

    @Test void wssEchoesBinaryMessages() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.wss("/game", serverSsl).handler(handler));
             TestClient client = TestClient.webSocket(server.port(), "wss://localhost/game", clientSsl, p -> {}).awaitHandshake()) {
            assertEquals("active", handler.next());
            client.send(new BinaryWebSocketFrame(Unpooled.copiedBuffer("secure", UTF_8)));
            assertEquals("read:secure", handler.next());
            assertEquals("binary:secure", client.next());
        }
    }

    @Test void tlsOverTcpWithFrontHandlersSeeingRawBytes() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        ByteCounter counter = new ByteCounter();
        AtomicReference<List<String>> names = new AtomicReference<>();
        HandlerChain framing = NetworkHandlers.lengthFieldFraming(1024);
        try (NetworkServer server = serve(ConnectionInitializer.tcp().ssl(serverSsl)
                .addFront("bytes", counter)
                .addLast(framing).addLast(STRING_CODEC)
                .customize(p -> names.set(p.names()))
                .handler(handler));
             TestClient client = new TestClient(server.port(), p -> {
                 p.addLast(clientSsl.newHandler(p.channel().alloc(), "localhost", server.port()));
                 framing.addTo(p);
                 STRING_CODEC.addTo(p);
             })) {
            assertEquals("active", handler.next());
            client.send("hello");
            assertEquals("read:hello", handler.next());
            assertEquals("echo:hello", client.next());

            List<String> order = names.get();
            assertTrue(order.indexOf("bytes") < order.indexOf(PipelineNames.SSL));
            assertTrue(order.indexOf(PipelineNames.SSL) < order.indexOf("frame-decoder"));
            assertTrue(order.indexOf("frame-decoder") < order.indexOf("string-decoder"));
            assertTrue(counter.bytes.get() > "hello".length() + 4, "the front section sees TLS records");
        }
    }

    @Test void plaintextClientNeverBecomesAConnection() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.wss("/game", serverSsl).handler(handler));
             TestClient client = new TestClient(server.port(), p -> {})) {
            client.send(Unpooled.copiedBuffer("GET /game HTTP/1.1\r\nHost: x\r\n\r\n", UTF_8));
            assertTrue(client.awaitClosed());
            assertNull(handler.poll(200));
        }
    }

    @Test void missingKeyStoreFailsWithNetworkException(@TempDir Path directory) {
        assertThrows(NetworkException.class, () -> NetworkSsl.server(directory.resolve("missing.p12"), PASSWORD));
    }

    @Test void clientSslContextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ConnectionInitializer.tcp().ssl(clientSsl));
    }

    @Sharable
    private static final class ByteCounter extends ChannelInboundHandlerAdapter {
        final AtomicLong bytes = new AtomicLong();

        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof ByteBuf buf) bytes.addAndGet(buf.readableBytes());
            ctx.fireChannelRead(message);
        }
    }
}
