package cn.managame.network.server;

import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.IConnectionHandler;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyInternalTcpServerTest {

    @Test
    void dispatchesConnectionAndMessagesToConnectionHandler() throws Exception {
        int port = availablePort();
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicReference<IConnection> connectedConnection = new AtomicReference<>();
        AtomicReference<IConnection> messageConnection = new AtomicReference<>();
        AtomicReference<Throwable> handlerException = new AtomicReference<>();
        AtomicBoolean receivedByteBuf = new AtomicBoolean(false);
        NetworkTcpServerConfig config = new NetworkTcpServerConfig(port);
        config.setHost("127.0.0.1");

        NettyInternalTcpServer server = new NettyInternalTcpServer(config, new IConnectionHandler() {
            @Override
            public void onConnect(IConnection connection) {
                connectedConnection.set(connection);
                connectLatch.countDown();
            }

            @Override
            public void onMessage(IConnection connection, Object packet) {
                messageConnection.set(connection);
                receivedByteBuf.set(packet instanceof ByteBuf);
                messageLatch.countDown();
                connection.writeMsg(packet);
            }

            @Override
            public void onDisconnect(IConnection connection) {
                disconnectLatch.countDown();
            }

            @Override
            public void onException(IConnection connection, Throwable cause) {
                handlerException.set(cause);
            }
        });

        try {
            server.start();

            byte[] request = new byte[]{1, 2, 3, 4};
            byte[] response = new byte[request.length];
            try(Socket socket = new Socket()){
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
                int read = socket.getInputStream().read(response);

                assertTrue(read > 0);
                assertArrayEquals(request, Arrays.copyOf(response, read));
            }

            assertTrue(connectLatch.await(3, TimeUnit.SECONDS));
            assertTrue(messageLatch.await(3, TimeUnit.SECONDS));
            assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS));
            assertTrue(receivedByteBuf.get());
            assertSame(connectedConnection.get(), messageConnection.get());
            assertNull(server.getSessionManager());
            assertNull(handlerException.get());
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
}
