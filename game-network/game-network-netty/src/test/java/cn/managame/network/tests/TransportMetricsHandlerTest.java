package cn.managame.network.tests;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

import org.junit.jupiter.api.Test;

class TransportMetricsHandlerTest {
    @Test
    void observesNativeEventsWithoutConsumingThemOrChangingWriteFailure() {
        var counters = new TransportMetricsHandler.Counters();
        var cause = new IllegalStateException("Simulated encoder failure");
        var channel =
                new EmbeddedChannel(
                        new ChannelOutboundHandlerAdapter() {
                            public void write(
                                    ChannelHandlerContext ctx,
                                    Object message,
                                    ChannelPromise promise) {
                                ReferenceCountUtil.release(message);
                                promise.setFailure(cause);
                            }
                        },
                        new TransportMetricsHandler(counters));
        try {
            assertEquals(1, counters.activeTransports.sum());
            var buffer = Unpooled.buffer(1).writeByte(1);
            var write = channel.writeAndFlush(buffer);
            assertSame(cause, write.cause());
            assertEquals(0, buffer.refCnt());
            assertEquals(1, counters.writeFailures.sum());
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            channel.runPendingTasks();
            assertEquals(1, counters.nonWritableTransitions.sum());
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
            assertEquals(1, counters.idleEvents.sum());
            channel.close();
            assertEquals(0, counters.activeTransports.sum());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
