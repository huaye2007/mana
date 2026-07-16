package cn.managame.network.server;

import cn.managame.network.connection.ConnectionType;
import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.IConnectionHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyWebSocketServerTest {

    @Test
    void websocketPresetUsesConnectionLifecycleAndEchoesFrames() throws Exception {
        int port = availablePort();
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(2);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicReference<ConnectionType> connectionType = new AtomicReference<>();
        AtomicInteger disconnectCount = new AtomicInteger();
        IConnectionHandler handler = new IConnectionHandler() {
            @Override public void onConnect(IConnection connection) {
                connectionType.set(connection.getType());
                connectLatch.countDown();
            }
            @Override public void onMessage(IConnection connection, Object packet) {
                messageLatch.countDown();
                connection.writeMsg(packet);
            }
            @Override public void onDisconnect(IConnection connection) {
                disconnectCount.incrementAndGet();
                disconnectLatch.countDown();
            }
            @Override public void onException(IConnection connection, Throwable cause) { connection.close(); }
            @Override public void onIdle(IConnection connection) { }
        };
        WebSocketServerProtocolConfig protocol = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/ws")
                .allowExtensions(true)
                .build();
        NettyServer server = NettyServer.webSocket(protocol, handler)
                .bind("127.0.0.1", port)
                .build();

        try {
            server.start();
            TestWebSocketListener listener = new TestWebSocketListener();
            WebSocket webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), listener)
                    .get(3, TimeUnit.SECONDS);

            assertTrue(connectLatch.await(3, TimeUnit.SECONDS));
            assertEquals(ConnectionType.WEBSOCKET, connectionType.get());

            webSocket.sendText("hello", true).get(3, TimeUnit.SECONDS);
            assertEquals("hello", listener.textFuture.get(3, TimeUnit.SECONDS));

            byte[] bytes = new byte[]{1, 2, 3, 4};
            webSocket.sendBinary(ByteBuffer.wrap(bytes), true).get(3, TimeUnit.SECONDS);
            assertArrayEquals(bytes, listener.binaryFuture.get(3, TimeUnit.SECONDS));

            assertTrue(messageLatch.await(3, TimeUnit.SECONDS));
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
            assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS));
            assertEquals(1, disconnectCount.get());
        } finally {
            server.stop();
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static class TestWebSocketListener implements WebSocket.Listener {
        private final CompletableFuture<String> textFuture = new CompletableFuture<>();
        private final CompletableFuture<byte[]> binaryFuture = new CompletableFuture<>();

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textFuture.complete(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binaryFuture.complete(Arrays.copyOf(bytes, bytes.length));
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            textFuture.completeExceptionally(error);
            binaryFuture.completeExceptionally(error);
        }
    }
}
