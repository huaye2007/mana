package cn.managame.network.netty;

import static cn.managame.network.netty.TestSupport.serve;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.TestSupport.RecordingHandler;
import cn.managame.network.netty.TestSupport.TestClient;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.logging.LoggingHandler;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PipelineConfigurationTest {
    @Test void nonSharableInstancesMustBeFactories() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HandlerChain.of("frame", new LengthFieldBasedFrameDecoder(1024, 0, 4)));
        assertTrue(error.getMessage().contains("not @Sharable"));
        HandlerChain.of("frame", () -> new LengthFieldBasedFrameDecoder(1024, 0, 4));
        HandlerChain.of("log", new LoggingHandler());
    }

    @Test void namesAreUniqueAndBuiltInNamesAreReserved() {
        assertThrows(IllegalArgumentException.class,
                () -> HandlerChain.builder().addLast("a", new StringDecoder()).addLast("a", new StringDecoder()));
        assertThrows(IllegalArgumentException.class, () -> ConnectionInitializer.tcp().addLast(PipelineNames.SSL, new LoggingHandler()));
        assertThrows(IllegalArgumentException.class, () -> ConnectionInitializer.tcp().addLast(PipelineNames.CONNECTION, new LoggingHandler()));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionInitializer.tcp().addFront("log", new LoggingHandler()).addLast("log", new LoggingHandler()));
        assertThrows(IllegalArgumentException.class, () -> ConnectionInitializer.tcp()
                .addLast(NetworkHandlers.lengthFieldFraming(64)).addLast(NetworkHandlers.lengthFieldFraming(64)));
        assertThrows(IllegalStateException.class, () -> ConnectionInitializer.tcp().build());
    }

    @Sharable private static final class FlushCounter extends ChannelOutboundHandlerAdapter {
        final AtomicInteger flushes = new AtomicInteger();
        @Override public void flush(ChannelHandlerContext ctx) {
            flushes.incrementAndGet();
            ctx.flush();
        }
    }

    @Test void writesOfOneTurnLeaveInOneFlushUnlessThePipelineDropsIt() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        FlushCounter counter = new FlushCounter();
        NetworkServer server = serve(ConnectionInitializer.tcp().addFront("count", counter).addLast("log", new LoggingHandler()).handler(handler));
        try (TestClient client = new TestClient(server.port(), pipeline -> {})) {
            assertEquals("active", handler.next());
            Connection connection = handler.connections.take();
            Channel channel = ((NettyConnection) connection).channel();
            List<String> names = channel.pipeline().names();
            assertTrue(names.indexOf(PipelineNames.FLUSH) < names.indexOf("log"), "after the transport, before the application");
            // A business thread writes while the EventLoop is busy: the writes and their flushes wait in its queue.
            CountDownLatch busy = new CountDownLatch(1);
            channel.eventLoop().execute(() -> {
                try { busy.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            int before = counter.flushes.get();
            for (int i = 0; i < 100; i++) assertEquals(WriteStatus.ACCEPTED, connection.write(Unpooled.copiedBuffer("x", UTF_8)));
            busy.countDown();
            int bytes = 0;
            while (bytes < 100) bytes += ((String) client.next()).length();
            assertEquals(1, counter.flushes.get() - before, "a hundred writes, one flush");
        } finally {
            server.close();
        }

        assertThrows(IllegalArgumentException.class, () -> ConnectionInitializer.tcp().addLast(PipelineNames.FLUSH, new LoggingHandler()));
        EmbeddedChannel plain = new EmbeddedChannel(ConnectionInitializer.tcp()
                .customize(pipeline -> pipeline.remove(PipelineNames.FLUSH)).handler(new RecordingHandler()).build());
        assertFalse(plain.pipeline().names().contains(PipelineNames.FLUSH));
        plain.finishAndReleaseAll();
    }

    @Test void anInitializerWorksWithoutASocket() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(ConnectionInitializer.tcp().handler(handler).build());
        assertEquals("active", handler.next());
        Connection connection = handler.connections.take();

        channel.writeInbound(Unpooled.copiedBuffer("x", UTF_8));
        assertEquals("read:x", handler.next());
        ByteBuf echoed = channel.readOutbound();
        assertEquals("x", echoed.toString(UTF_8));
        echoed.release();

        channel.close();
        assertEquals("inactive", handler.next());
        assertFalse(connection.isActive());
    }

    @Test void listenersShareApplicationEventLoopsAndHandlerChains() throws Exception {
        EventLoopGroup boss = NetworkTransports.newEventLoopGroup(1, "shared-boss");
        EventLoopGroup worker = NetworkTransports.newEventLoopGroup(2, "shared-worker");
        RecordingHandler handler = new RecordingHandler();
        HandlerChain upperCase = HandlerChain.of("upper", UpperCase::new);
        try {
            NetworkServer tcp = serve(boss, worker, ConnectionInitializer.tcp()
                    .addLast(NetworkHandlers.lengthFieldFraming(1024)).addLast(upperCase).handler(handler));
            NetworkServer ws = serve(boss, worker, ConnectionInitializer.ws("/game").addLast(upperCase).handler(handler));

            tcp.close();
            assertFalse(boss.isShuttingDown() || worker.isShuttingDown(), "the application's EventLoops keep running");

            try (ws; TestClient client = TestClient.webSocket(ws.port(), "ws://127.0.0.1/game", null, p -> {}).awaitHandshake()) {
                assertEquals("active", handler.next());
                client.send(new BinaryWebSocketFrame(Unpooled.copiedBuffer("hi", UTF_8)));
                assertEquals("read:HI", handler.next());
            }
        } finally {
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test void customizerCanInsertAroundBuiltInHandlers() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.ws("/game")
                .customize(p -> p.addAfter(PipelineNames.WEBSOCKET_MESSAGES, "upper", new UpperCase()))
                .handler(handler));
             TestClient client = TestClient.webSocket(server.port(), "ws://127.0.0.1/game", null, p -> {}).awaitHandshake()) {
            assertEquals("active", handler.next());
            client.send(new BinaryWebSocketFrame(Unpooled.copiedBuffer("abc", UTF_8)));
            assertEquals("read:ABC", handler.next());
        }
    }

    /** Not sharable: a new instance per connection. */
    private static final class UpperCase extends SimpleChannelInboundHandler<ByteBuf> {
        @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
            ctx.fireChannelRead(Unpooled.copiedBuffer(message.toString(UTF_8).toUpperCase(), UTF_8));
        }
    }
}
