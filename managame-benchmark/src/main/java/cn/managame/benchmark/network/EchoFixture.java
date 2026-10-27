package cn.managame.benchmark.network;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkHandlers;
import cn.managame.network.netty.NetworkServer;
import cn.managame.network.netty.NetworkTransports;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.flush.FlushConsolidationHandler;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A server that echoes every length-framed message and clients holding {@code connections} connections to it,
 * all in this process over loopback, with flush consolidation as RPC has it. Each echo runs {@code onEcho}.
 * Frames are duplicates of one payload, so the benchmarks measure the transport, not the making of messages.
 */
final class EchoFixture implements AutoCloseable {
    private static final int MAX_FRAME = 8 * 1024 * 1024;
    /** What rpc sets, so that both see the same back pressure; Netty's own 64 KiB refuses a window of 1024 at once. */
    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(1 << 20, 4 << 20);
    private final NetworkServer server;
    private final EventLoopGroup clientGroup;
    private final List<NetworkClient> clients = new ArrayList<>();
    final List<Connection> open = new CopyOnWriteArrayList<>();
    final ByteBuf payload;

    EchoFixture(int payloadSize, int connections, Runnable onEcho) throws InterruptedException {
        payload = Unpooled.directBuffer(payloadSize).writeZero(payloadSize);
        server = NetworkServer.start(new ServerBootstrap().childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                .childHandler(pipeline(new Handler() {
                    @Override public void onRead(Connection connection, Object message) {
                        ByteBuf frame = ((ByteBuf) message).retain();
                        if (connection.write(frame) != WriteStatus.ACCEPTED) frame.release();
                    }
                })), new InetSocketAddress("127.0.0.1", 0));
        clientGroup = NetworkTransports.newEventLoopGroup(0, "echo-client");
        Bootstrap bootstrap = new Bootstrap().group(clientGroup).option(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                .handler(pipeline(new Handler() {
                    @Override public void onActive(Connection connection) { open.add(connection); }

                    @Override public void onRead(Connection connection, Object message) { onEcho.run(); }
                }));
        for (int i = 0; i < connections; i++)
            clients.add(NetworkClient.connect(bootstrap, new InetSocketAddress("127.0.0.1", server.port())));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (open.size() < connections) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("The connections did not open");
            Thread.sleep(10);
        }
    }

    private static ConnectionInitializer pipeline(ConnectionHandler handler) {
        return ConnectionInitializer.tcp()
                .addLast("flush", () -> new FlushConsolidationHandler(FlushConsolidationHandler.DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES, true))
                .addLast(NetworkHandlers.lengthFieldFraming(MAX_FRAME))
                .handler(handler)
                .build();
    }

    /** Sends a duplicate of the payload on the {@code index}-th connection, modulo their number. */
    boolean send(int index) {
        ByteBuf frame = payload.retainedDuplicate();
        if (open.get(index % open.size()).write(frame) == WriteStatus.ACCEPTED) return true;
        frame.release();
        return false;
    }

    @Override public void close() {
        clients.forEach(NetworkClient::close);
        clientGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        server.close();
        payload.release();
    }

    private abstract static class Handler implements ConnectionHandler {
        @Override public void onActive(Connection connection) {}

        @Override public void onInActive(Connection connection) {}

        @Override public void onException(Connection connection, Throwable cause) { connection.close(); }
    }
}
