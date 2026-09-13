package cn.managame.network.tests;

import cn.managame.network.ConnectionType;
import cn.managame.network.netty.connection.NettyConnection;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WriteCompletionTest {
    @Test void acceptedWriteReportsDelayedFailureAndTransportOwnsBuffer() {
        AtomicReference<ChannelPromise> pending = new AtomicReference<>();
        AtomicReference<Object> written = new AtomicReference<>();
        var channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                written.set(message); pending.set(promise);
            }
        });
        try {
            var connection = new NettyConnection(channel, ConnectionType.TCP);
            var buffer = Unpooled.buffer(1).writeByte(1);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<Throwable> seen = new AtomicReference<>();
            assertTrue(connection.write(buffer, error -> { seen.set(error); callbacks.incrementAndGet(); }));
            assertEquals(0, callbacks.get());
            var error = new IllegalStateException("asynchronous socket failure");
            ReferenceCountUtil.release(written.get());
            pending.get().setFailure(error);
            channel.runPendingTasks();
            assertEquals(1, callbacks.get());
            assertSame(error, seen.get());
            assertEquals(0, buffer.refCnt());
            assertFalse(pending.get().trySuccess());
            assertEquals(1, callbacks.get());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void completedWriteReportsSuccessAndRejectedWriteRetainsOwnership() {
        var channel = new EmbeddedChannel();
        try {
            var connection = new NettyConnection(channel, ConnectionType.TCP);
            AtomicInteger callbacks = new AtomicInteger();
            var sent = Unpooled.buffer(1).writeByte(1);
            assertTrue(connection.write(sent, error -> { assertNull(error); callbacks.incrementAndGet(); }));
            channel.runPendingTasks();
            assertEquals(1, callbacks.get());
            assertSame(sent, channel.readOutbound());
            sent.release();
            connection.close();
            var rejected = Unpooled.buffer(1).writeByte(2);
            try {
                assertFalse(connection.write(rejected, error -> callbacks.incrementAndGet()));
                assertEquals(1, callbacks.get());
                assertEquals(1, rejected.refCnt());
            } finally { rejected.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void observerFailureCannotEscapeTheTransportOrChangeOwnership() {
        var channel = new EmbeddedChannel();
        try {
            var connection = new NettyConnection(channel, ConnectionType.TCP);
            var sent = Unpooled.buffer(1).writeByte(1);
            assertTrue(connection.write(sent, error -> { throw new IllegalStateException("observer"); }));
            assertDoesNotThrow(channel::runPendingTasks);
            assertSame(sent, channel.readOutbound());
            sent.release();
            assertTrue(connection.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }
}
