package cn.managame.network.tests;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;
import cn.managame.network.netty.*;

import io.netty.buffer.*;
import io.netty.channel.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;

@Timeout(20)
class SlowPeerTest {
    @Test
    void nativeWatermarksReportSlowReaderAndCloseReleasesPendingWrites() throws Exception {
        try (var resources = NetworkResources.builder().ioThreads(2).build()) {
            var accepted = new CompletableFuture<Connection>();
            var nonWritable = new CountDownLatch(1);
            var server =
                    TcpNetworkServer.builder()
                            .resources(resources)
                            .listen("127.0.0.1", 0)
                            .childOption(ChannelOption.SO_SNDBUF, 1024)
                            .childOption(
                                    ChannelOption.WRITE_BUFFER_WATER_MARK,
                                    new WriteBufferWaterMark(1024, 2048))
                            .pipeline(
                                    (c, p) ->
                                            p.addLast(
                                                    new ChannelInboundHandlerAdapter() {
                                                        public void channelWritabilityChanged(
                                                                ChannelHandlerContext ctx) {
                                                            if (!ctx.channel().isWritable())
                                                                nonWritable.countDown();
                                                            ctx.fireChannelWritabilityChanged();
                                                        }
                                                    }))
                            .handlerFactory(
                                    () ->
                                            new NetworkHandler() {
                                                public void onConnected(Connection c) {
                                                    accepted.complete(c);
                                                }

                                                public void onMessage(
                                                        Connection c, Object message) {}
                                            })
                            .build();
            var client =
                    TcpNetworkClient.builder()
                            .resources(resources)
                            .option(ChannelOption.AUTO_READ, false)
                            .option(ChannelOption.SO_RCVBUF, 1024)
                            .handlerFactory(() -> (c, m) -> {})
                            .build();
            var messages = new ArrayList<ByteBuf>();
            try {
                server.start();
                client.init();
                var connected = new CompletableFuture<Connection>();
                client.connect(
                        "127.0.0.1",
                        server.boundAddresses().values().iterator().next().getPort(),
                        new ConnectCallback() {
                            public void onSuccess(Connection c) {
                                connected.complete(c);
                            }

                            public void onFailure(Throwable t) {
                                connected.completeExceptionally(t);
                            }
                        });
                connected.get(5, TimeUnit.SECONDS);
                Connection connection = accepted.get(5, TimeUnit.SECONDS);
                var ch = NettyAccess.channel(connection);
                ch.eventLoop()
                        .submit(
                                () -> {
                                    for (int i = 0; i < 256; i++) {
                                        ByteBuf message =
                                                Unpooled.directBuffer(65536).writeZero(65536);
                                        messages.add(message);
                                        if (!connection.write(message)) {
                                            message.release();
                                            fail("Write unexpectedly rejected");
                                        }
                                    }
                                })
                        .sync();
                assertTrue(nonWritable.await(5, TimeUnit.SECONDS));
                assertTrue(messages.stream().anyMatch(b -> b.refCnt() > 0));
                connection.close();
                ch.closeFuture().sync();
                ch.eventLoop().submit(() -> {}).sync();
                assertTrue(messages.stream().allMatch(b -> b.refCnt() == 0));
            } finally {
                client.destroy();
                server.stop();
            }
        }
    }
}
