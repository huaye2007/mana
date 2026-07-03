package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.handler.INetworkHandler;
import com.github.huaye2007.mana.network.session.ISession;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyTcpServerTest {

    @Test
    void startsEchoesByteArrayAndStops() throws Exception {
        int port = availablePort();
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicBoolean receivedByteBuf = new AtomicBoolean(false);
        NetworkTcpServerConfig config = new NetworkTcpServerConfig(port);
        config.setHost("127.0.0.1");
        NettyTcpServer server = new NettyTcpServer(config, new INetworkHandler() {
            @Override
            public void onConnect(ISession session) {
            }

            @Override
            public void onMessage(ISession session, Object packet) {
                receivedByteBuf.set(packet instanceof ByteBuf);
                messageLatch.countDown();
                session.writeMsg(packet);
            }

            @Override
            public void onDisconnect(ISession session) {
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

            byte[] request = new byte[]{7, 8, 9, 10};
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

            assertTrue(messageLatch.await(3, TimeUnit.SECONDS));
            assertTrue(receivedByteBuf.get());
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
