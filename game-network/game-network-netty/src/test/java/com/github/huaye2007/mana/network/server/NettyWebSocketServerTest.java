package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.connection.ConnectionType;
import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.handler.INetworkHandler;
import com.github.huaye2007.mana.network.session.ISession;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyWebSocketServerTest {

    @Test
    void websocketUsesSameLifecycleAndEchoesFrames() throws Exception {
        int port = availablePort();
        NetworkWsServerConfig config = new NetworkWsServerConfig(port);
        config.setHost("127.0.0.1");
        config.setWebsocketPath("/ws");

        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(2);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicReference<ConnectionType> connectionType = new AtomicReference<>();
        AtomicInteger disconnectCount = new AtomicInteger();
        NettyWebSocketServer server = new NettyWebSocketServer(config, new INetworkHandler() {
            @Override
            public void onConnect(ISession session) {
                connectionType.set(session.getConnection().getType());
                connectLatch.countDown();
            }

            @Override
            public void onMessage(ISession session, Object packet) {
                messageLatch.countDown();
                session.writeMsg(packet);
            }

            @Override
            public void onDisconnect(ISession session) {
                disconnectCount.incrementAndGet();
                disconnectLatch.countDown();
            }

            @Override
            public void onException(ISession session, Throwable cause) {
            }

            @Override
            public ISession createSession(IConnection connection) {
                return null;
            }
        });

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
            assertEquals(0, server.getConnectionManager().size());
            assertEquals(0, server.getSessionManager().size());
        } finally {
            server.stop();
        }
    }

    private static int availablePort() throws IOException {
        try(ServerSocket socket = new ServerSocket(0)){
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static class TestWebSocketListener implements WebSocket.Listener {
        private final CompletableFuture<String> textFuture = new CompletableFuture<>();
        private final CompletableFuture<byte[]> binaryFuture = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textFuture.complete(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binaryFuture.complete(Arrays.copyOf(bytes, bytes.length));
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            textFuture.completeExceptionally(error);
            binaryFuture.completeExceptionally(error);
        }
    }
}
