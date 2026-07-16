package cn.managame.network.server;

import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.IConnectionHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyTcpServerTest {

    @Test
    void standardTcpPresetEchoesMessages() throws Exception {
        int port = availablePort();
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicBoolean receivedByteBuf = new AtomicBoolean();
        NettyServer server = NettyServer.tcp(echoHandler(messageLatch, receivedByteBuf))
                .bind("127.0.0.1", port)
                .build();

        try {
            server.start();
            assertArrayEquals(new byte[]{7, 8, 9, 10}, exchange(port, new byte[]{7, 8, 9, 10}));
            assertTrue(messageLatch.await(3, TimeUnit.SECONDS));
            assertTrue(receivedByteBuf.get());
        } finally {
            server.stop();
        }
    }

    @Test
    void multipleServersHaveIndependentLifecycles() throws Exception {
        int firstPort = availablePort();
        int secondPort = availablePort();
        NettyServer first = NettyServer.tcp(echoHandler(null, null)).bind("127.0.0.1", firstPort).build();
        NettyServer second = NettyServer.tcp(echoHandler(null, null)).bind("127.0.0.1", secondPort).build();
        try {
            first.start();
            second.start();
            assertArrayEquals(new byte[]{1}, exchange(firstPort, new byte[]{1}));
            assertArrayEquals(new byte[]{2}, exchange(secondPort, new byte[]{2}));

            first.stop();
            assertFalse(first.isRunning());
            assertTrue(second.isRunning());
            assertArrayEquals(new byte[]{3}, exchange(secondPort, new byte[]{3}));
        } finally {
            first.stop();
            second.stop();
        }
    }

    @Test
    void customInitializerCanReplacePresetPipeline() throws Exception {
        int port = availablePort();
        NettyServer server = NettyServer.custom(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                                ctx.writeAndFlush(msg);
                            }
                        });
                    }
                })
                .bind("127.0.0.1", port)
                .build();
        try {
            server.start();
            assertArrayEquals(new byte[]{4, 5}, exchange(port, new byte[]{4, 5}));
        } finally {
            server.stop();
        }
    }

    private static IConnectionHandler echoHandler(CountDownLatch latch, AtomicBoolean receivedByteBuf) {
        return new IConnectionHandler() {
            @Override public void onConnect(IConnection connection) { }
            @Override public void onMessage(IConnection connection, Object packet) {
                if (receivedByteBuf != null) receivedByteBuf.set(packet instanceof ByteBuf);
                if (latch != null) latch.countDown();
                connection.writeMsg(packet);
            }
            @Override public void onDisconnect(IConnection connection) { }
            @Override public void onException(IConnection connection, Throwable cause) { connection.close(); }
            @Override public void onIdle(IConnection connection) { }
        };
    }

    private static byte[] exchange(int port, byte[] request) throws IOException {
        byte[] response = new byte[request.length];
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            int read = socket.getInputStream().read(response);
            if (read <= 0) throw new IOException("server returned no data");
            return Arrays.copyOf(response, read);
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
