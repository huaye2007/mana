package cn.managame.network.netty;

import static cn.managame.network.netty.TestSupport.serve;
import static cn.managame.network.netty.TestSupport.STRING_CODEC;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.connection.Connection;
import cn.managame.network.netty.TestSupport.RecordingHandler;
import cn.managame.network.netty.TestSupport.TestClient;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebSocketServerTest {
    @Test void echoesBinaryMessagesAndExposesTheUpgradeRequest() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws("/game").handler(handler));
             TestClient client = TestClient.webSocket(server.port(), "ws://127.0.0.1/game?token=abc", null, p -> {})
                     .awaitHandshake()) {
            assertEquals("active", handler.next());
            Connection connection = handler.connections.take();
            assertEquals("/game?token=abc", connection.get(NetworkAttributes.WEBSOCKET_HANDSHAKE).requestUri());

            client.send(new BinaryWebSocketFrame(Unpooled.copiedBuffer("hi", UTF_8)));
            assertEquals("read:hi", handler.next());
            assertEquals("binary:hi", client.next());
        }
    }

    @Test void textModeReusesTheSameCodecAsTcp() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws(WebSocketConfig.of("/game").withMessages(WebSocketMessages.TEXT))
                .addLast(STRING_CODEC).handler(handler));
             TestClient client = TestClient.webSocket(server.port(), "ws://127.0.0.1/game", null, p -> {}).awaitHandshake()) {
            assertEquals("active", handler.next());
            client.send(new TextWebSocketFrame("hello"));
            assertEquals("read:hello", handler.next());
            assertEquals("text:echo:hello", client.next());
        }
    }

    @Test void framesModeHandsFramesToTheApplication() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws(WebSocketConfig.of("/game").withMessages(WebSocketMessages.FRAMES))
                .handler(handler));
             TestClient client = TestClient.webSocket(server.port(), "ws://127.0.0.1/game", null, p -> {}).awaitHandshake()) {
            assertEquals("active", handler.next());
            client.send(new TextWebSocketFrame("hello"));
            assertEquals("read:frame:hello", handler.next());
            assertEquals("text:echo:hello", client.next());
        }
    }

    @Test void wrongFrameTypeClosesWith1003() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws("/game").handler(handler));
             TestClient client = TestClient.webSocket(server.port(), "ws://127.0.0.1/game", null, p -> {}).awaitHandshake()) {
            assertEquals("active", handler.next());
            client.send(new TextWebSocketFrame("not binary"));
            assertEquals("close:1003", client.next());
            assertTrue(client.awaitClosed());
            assertEquals("inactive", handler.next());
        }
    }

    @Test void oversizedMessageClosesWith1009() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws(WebSocketConfig.of("/game").withMaxMessageSize(16))
                .handler(handler));
             TestClient client = TestClient.webSocket(server.port(), "ws://127.0.0.1/game", null, p -> {}).awaitHandshake()) {
            assertEquals("active", handler.next());
            client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[17])));
            assertEquals("close:1009", client.next());
            assertTrue(client.awaitClosed());
        }
    }

    @Test void otherPathsGet404WithoutAConnection() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws("/game").handler(handler));
             TestClient client = new TestClient(server.port(), p -> p.addLast(new HttpClientCodec(), new HttpObjectAggregator(8192)))) {
            client.send(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/game/other"));
            assertEquals("http:404", client.next());
            assertTrue(client.awaitClosed());
            assertNull(handler.poll(200));
        }
    }

    @Test void silentClientIsClosedByTheReadTimeoutWithoutOnActive() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws("/game")
                .addLast(NetworkHandlers.readTimeout(Duration.ofMillis(200))).handler(handler));
             TestClient client = new TestClient(server.port(), p -> {})) {
            assertTrue(client.awaitClosed());
            assertNull(handler.poll(200));
        }
    }

    @Test void aCustomHandlerCanRejectTheHandshakeBeforeOnActive() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        // A plain Netty handler doing login on the upgrade request: no token, no connection.
        ChannelInboundHandlerAdapter auth = new ChannelInboundHandlerAdapter() {
            @Override public boolean isSharable() { return true; }
            @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                if (event instanceof HandshakeComplete handshake
                        && !new QueryStringDecoder(handshake.requestUri()).parameters().containsKey("token")) {
                    ctx.close();
                    return;
                }
                ctx.fireUserEventTriggered(event);
            }
        };
        try (NetworkServer server = serve(ConnectionInitializer.ws("/game")
                .addLast("auth", auth).handler(handler))) {
            try (TestClient rejected = TestClient.webSocket(server.port(), "ws://127.0.0.1/game", null, p -> {})) {
                assertTrue(rejected.awaitClosed());
                assertNull(handler.poll(200));
            }
            try (TestClient accepted = TestClient.webSocket(server.port(), "ws://127.0.0.1/game?token=t", null, p -> {})
                    .awaitHandshake()) {
                assertEquals("active", handler.next());
            }
        }
    }

    @Test void pathsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> WebSocketConfig.of("game"));
        assertThrows(IllegalArgumentException.class, () -> WebSocketConfig.of("/game?x=1"));
        assertThrows(IllegalArgumentException.class, () -> WebSocketConfig.of("//host/game"));
        assertThrows(IllegalArgumentException.class, () -> WebSocketConfig.of("/game").withMaxMessageSize(0));
    }
}
