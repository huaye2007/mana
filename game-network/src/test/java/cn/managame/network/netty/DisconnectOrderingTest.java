package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DisconnectOrderingTest extends NetworkTestSupport {
    private static final class RecordingHandler implements ConnectionHandler {
        final List<String> order = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final CountDownLatch disconnected = new CountDownLatch(1);
        final boolean failMessage;
        ByteBuf borrowed;

        RecordingHandler(boolean failMessage) { this.failMessage = failMessage; }
        private void record(String event) {
            threads.add(Thread.currentThread());
            order.add(event);
        }
        public void onConnected(Connection connection) { record("connected"); }
        public void onMessage(Connection connection, Object message) {
            borrowed = (ByteBuf) message;
            record("message:" + borrowed.toString(StandardCharsets.UTF_8) + ":" + connection.isActive());
            if (failMessage) throw new IllegalStateException("message failure");
        }
        public void onException(Connection connection, Throwable cause) {
            errors.add(cause);
            record("error");
        }
        public void onDisconnected(Connection connection) {
            record("disconnected");
            disconnected.countDown();
        }
    }

    private static class EofDecoder extends ByteToMessageDecoder {
        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {}
        @Override protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (in.isReadable()) out.add(in.readRetainedSlice(in.readableBytes()));
        }
    }

    private static EmbeddedChannel channel(RecordingHandler handler, ByteToMessageDecoder decoder) {
        return new EmbeddedChannel(initializer(handler, p -> p.addLast(decoder)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tailMessagePrecedesDisconnectAndReleasesBorrowedReference(boolean failMessage) {
        RecordingHandler handler = new RecordingHandler(failMessage);
        EmbeddedChannel channel = channel(handler, new EofDecoder());
        ByteBuf input = Unpooled.copiedBuffer("tail", StandardCharsets.UTF_8);
        var ctx = channel.pipeline().context(NetworkChannelInitializer.HANDLER);
        var adapter = (ConnectionHandlerAdapter) ctx.handler();
        try {
            channel.writeInbound(input);
            assertEquals(List.of("connected"), handler.order);
            channel.close();
            channel.runPendingTasks();
            List<String> expected = failMessage
                    ? List.of("connected", "message:tail:false", "error", "disconnected")
                    : List.of("connected", "message:tail:false", "disconnected");
            assertEquals(expected, handler.order);
            assertEquals(0, input.refCnt());
            assertEquals(0, handler.borrowed.refCnt());
            assertEquals(failMessage ? 1 : 0, handler.errors.size());
            channel.close();
            adapter.channelInactive(ctx);
            // Deliver directly to the terminal adapter to exercise its late-message ownership guard.
            ByteBuf anotherLate = Unpooled.buffer().writeByte(2);
            adapter.channelRead(ctx, anotherLate);
            assertEquals(0, anotherLate.refCnt());
            assertEquals(expected, handler.order);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test void decodeLastFailureStillDeliversProducedTailAndDisconnects() {
        RecordingHandler handler = new RecordingHandler(false);
        IllegalStateException failure = new IllegalStateException("invalid trailing bytes");
        EmbeddedChannel channel = channel(handler, new EofDecoder() {
            @Override protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                if (in.isReadable()) out.add(in.readRetainedSlice(in.readableBytes()));
                throw failure;
            }
        });
        ByteBuf input = Unpooled.copiedBuffer("tail", StandardCharsets.UTF_8);
        try {
            channel.writeInbound(input);
            channel.close();
            channel.runPendingTasks();
            assertEquals(List.of("connected", "message:tail:false", "disconnected"), handler.order);
            // Netty propagates inactive from its cleanup before reporting decodeLast failure.
            // The subsequent exception is diagnostic only, after business delivery has ended.
            assertTrue(handler.errors.isEmpty());
            assertEquals(0, handler.borrowed.refCnt());
            assertEquals(0, input.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test void orderedOffloadedDecoderCompletesBeforeEventLoopDisconnectCallback() throws Exception {
        var decoderGroup = new DefaultEventExecutorGroup(1);
        CountDownLatch decodingLast = new CountDownLatch(1);
        CountDownLatch resumeDecoder = new CountDownLatch(1);
        RecordingHandler handler = new RecordingHandler(false);
        AtomicReference<Channel> accepted = new AtomicReference<>();
        try (var server = NetworkServer.builder().bindAddress(LOCAL).handler(handler).pipeline(p -> {
                 accepted.set(p.channel());
                 p.addLast(decoderGroup, "eof-decoder", new EofDecoder() {
                     @Override protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
                             throws Exception {
                         decodingLast.countDown();
                         if (!resumeDecoder.await(5, TimeUnit.SECONDS))
                             throw new IllegalStateException("Decoder was not released");
                         super.decodeLast(ctx, in, out);
                     }
                 });
             }).build();
             var socket = new Socket()) {
            server.start();
            socket.connect(server.localAddress());
            socket.getOutputStream().write("tail".getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            try {
                assertTrue(decodingLast.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("connected"), handler.order,
                        "Physical inactivity must not overtake the queued decoder");
            } finally {
                resumeDecoder.countDown();
            }
            assertTrue(handler.disconnected.await(5, TimeUnit.SECONDS));
            accepted.get().eventLoop().submit(() -> {}).sync();
            assertEquals(List.of("connected", "message:tail:false", "disconnected"), handler.order);
            assertEquals(0, handler.borrowed.refCnt());
            assertTrue(handler.errors.isEmpty());
            assertTrue(handler.threads.stream().allMatch(accepted.get().eventLoop()::inEventLoop));
        } finally {
            resumeDecoder.countDown();
            decoderGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}

