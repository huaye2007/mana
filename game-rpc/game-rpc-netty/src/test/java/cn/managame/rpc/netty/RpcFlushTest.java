package cn.managame.rpc.netty;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.rpc.*;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.concurrent.*;

class RpcFlushTest {
    static class FlushCounter extends ChannelOutboundHandlerAdapter {
        int flushes;

        @Override
        public void flush(ChannelHandlerContext ctx) {
            flushes++;
            ctx.flush();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredPipelinePreservesOrderAndFlushesAtReadComplete(boolean consolidate) {
        var counter = new FlushCounter();
        var channel = new EmbeddedChannel(counter);
        NettyRpcTransport.configurePipeline(channel.pipeline(), 128, consolidate);
        var writes = new ArrayList<ChannelFuture>();
        try {
            // Model one inbound batch: outbound replies share its read-complete flush.
            channel.pipeline().fireChannelRead(Unpooled.EMPTY_BUFFER);
            for (int i = 0; i < 8; i++)
                writes.add(channel.pipeline().writeAndFlush(Unpooled.buffer(4).writeInt(i)));
            assertEquals(consolidate ? 0 : 8, counter.flushes);
            if (consolidate) assertTrue(writes.stream().noneMatch(ChannelFuture::isDone));
            channel.pipeline().fireChannelReadComplete();
            channel.runPendingTasks();
            assertEquals(consolidate ? 1 : 8, counter.flushes);
            assertTrue(writes.stream().allMatch(ChannelFuture::isSuccess));
            for (int i = 0; i < 8; i++) {
                ByteBuf prefix = channel.readOutbound(), payload = channel.readOutbound();
                try {
                    assertEquals(4, prefix.readInt());
                    assertEquals(i, payload.readInt());
                } finally {
                    prefix.release();
                    payload.release();
                }
            }
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void thresholdFlushAndCloseDoNotLeavePendingWrites() {
        var counter = new FlushCounter();
        var channel = new EmbeddedChannel(counter);
        NettyRpcTransport.configurePipeline(channel.pipeline(), 128, true);
        try {
            channel.pipeline().fireChannelRead(Unpooled.EMPTY_BUFFER);
            for (int i = 0; i < 256; i++)
                channel.pipeline().writeAndFlush(Unpooled.buffer(1).writeByte(i));
            assertEquals(1, counter.flushes);
            var last = channel.pipeline().writeAndFlush(Unpooled.buffer(1).writeByte(1));
            assertFalse(last.isDone());
            channel.close();
            assertTrue(last.isSuccess());
            assertEquals(2, counter.flushes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realTcpHandshakeAndSingleCallCompleteInBothModes(boolean consolidate) throws Exception {
        var fixture = new RpcTcpTest();
        var b =
                fixture.builder(
                                20,
                                (c, m) -> fixture.reply((RpcMessage) m, RpcTcpTest.body("reply")))
                        .consolidateFlush(consolidate)
                        .build();
        fixture.nodes.add(b);
        var a = fixture.builder(10, (c, m) -> {}).consolidateFlush(consolidate).build();
        fixture.nodes.add(a);
        try {
            fixture.connect(a, b, 1);
            var result = new CompletableFuture<RpcResult>();
            a.call(
                    20,
                    1,
                    RpcTcpTest.body("single message"),
                    RpcOptions.DEFAULT,
                    r -> result.complete(RpcTcpTest.snapshot(r)));
            assertTrue(result.get(2, TimeUnit.SECONDS).isSuccess());
            assertEquals(RpcTcpTest.body("reply"), result.get().value().body());
        } finally {
            fixture.nodes.reversed().forEach(RpcNode::close);
        }
    }
}
