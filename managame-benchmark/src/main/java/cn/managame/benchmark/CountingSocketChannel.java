package cn.managame.benchmark;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.concurrent.atomic.LongAdder;

/**
 * An NIO socket channel that counts the bytes it writes and reads, for the side of a benchmark that dials, so that
 * {@link CostProfiler} can tell the bytes on the wire per operation both ways. Its own handler sits first in its
 * pipeline, next to the socket, whatever the code under test adds after it; TCP and IP headers are not counted.
 * Bootstraps take it with {@code channel(CountingSocketChannel.class)} on a group from {@link #newGroup}.
 */
public final class CountingSocketChannel extends NioSocketChannel {
    static final LongAdder SENT = new LongAdder(), RECEIVED = new LongAdder();
    private static final Counter COUNTER = new Counter();

    public CountingSocketChannel() { pipeline().addFirst("bench-bytes", COUNTER); }

    /** NIO EventLoops, Netty's default number of them, named {@code <name>-<n>-<m>}. */
    public static EventLoopGroup newGroup(String name) {
        return new MultiThreadIoEventLoopGroup(0, new DefaultThreadFactory(name), NioIoHandler.newFactory());
    }

    @Sharable
    private static final class Counter extends ChannelDuplexHandler {
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) RECEIVED.add(buf.readableBytes());
            ctx.fireChannelRead(msg);
        }

        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf buf) SENT.add(buf.readableBytes());
            ctx.write(msg, promise);
        }
    }
}
