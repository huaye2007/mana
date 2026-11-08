package cn.managame.network.netty;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import java.io.IOException;
import java.util.concurrent.atomic.LongAdder;

/**
 * What one listener reports: the first handler of each of its connections, which sees the bytes on the wire, and what
 * the terminal handlers call when a connection fails. A listener without a Metrics holds {@link #NONE}, which no
 * pipeline does, so it pays nothing per message. Public only to be reachable from the http package; not API.
 */
@Sharable
public final class ListenerMetrics extends ChannelDuplexHandler {
    public static final ListenerMetrics NONE = new ListenerMetrics(Metrics.noop(), "none");
    private final Counter opened, busy, bytesRead, bytesWritten, handshakesFailed, ioExceptions, decodeExceptions, otherExceptions;
    private final LongAdder active = new LongAdder();

    private ListenerMetrics(Metrics metrics, String listener) {
        opened = metrics.counter("managame.network.connections.opened", "listener", listener);
        busy = metrics.counter("managame.network.connections.busy", "listener", listener);
        bytesRead = metrics.counter("managame.network.bytes.read", "listener", listener);
        bytesWritten = metrics.counter("managame.network.bytes.written", "listener", listener);
        handshakesFailed = metrics.counter("managame.network.handshakes.failed", "listener", listener);
        ioExceptions = exceptions(metrics, listener, "io");
        decodeExceptions = exceptions(metrics, listener, "decode");
        otherExceptions = exceptions(metrics, listener, "other");
        metrics.gauge("managame.network.connections.active", active::sum, "listener", listener);
    }

    private static Counter exceptions(Metrics metrics, String listener, String kind) {
        return metrics.counter("managame.network.exceptions", "listener", listener, "kind", kind);
    }

    /** The meters of the listener {@code name}, made once here at build; {@link #NONE} under {@link Metrics#noop()}. */
    public static ListenerMetrics of(Metrics metrics, String name) {
        return metrics == Metrics.noop() ? NONE : new ListenerMetrics(metrics, name);
    }

    /** A TLS handshake failed, or a WebSocket one did not finish in time. */
    public void handshakeFailed() { handshakesFailed.increment(); }

    /** An exception on a connection that is open: of the network, of decoding what arrived, or anything else. */
    public void exception(Throwable cause) {
        (cause instanceof IOException ? ioExceptions : cause instanceof DecoderException ? decodeExceptions : otherExceptions).increment();
    }

    @Override public void channelActive(ChannelHandlerContext ctx) {
        opened.increment();
        active.increment();
        ctx.fireChannelActive();
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        active.decrement();
        ctx.fireChannelInactive();
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof ByteBuf bytes) bytesRead.increment(bytes.readableBytes());
        ctx.fireChannelRead(message);
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (message instanceof ByteBuf bytes) bytesWritten.increment(bytes.readableBytes());
        ctx.write(message, promise);
    }

    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (!ctx.channel().isWritable()) busy.increment();
        ctx.fireChannelWritabilityChanged();
    }
}
