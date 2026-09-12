package cn.managame.network.tests;

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;

import java.util.concurrent.atomic.LongAdder;

/** Application example, not installed by the component. Create one handler per Channel. */
public final class TransportMetricsHandler extends ChannelDuplexHandler {
    public static final class Counters {
        public final LongAdder activeTransports = new LongAdder();
        public final LongAdder websocketHandshakes = new LongAdder();
        public final LongAdder tlsFailures = new LongAdder();
        public final LongAdder handshakeTimeouts = new LongAdder();
        public final LongAdder writeFailures = new LongAdder();
        public final LongAdder nonWritableTransitions = new LongAdder();
        public final LongAdder idleEvents = new LongAdder();
        public final LongAdder exceptions = new LongAdder();
    }

    private final Counters counters;

    public TransportMetricsHandler(Counters counters) {
        this.counters = counters;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        SslHandler tls = ctx.pipeline().get(SslHandler.class);
        if (tls != null)
            tls.handshakeFuture()
                    .addListener(
                            f -> {
                                if (!f.isSuccess()) counters.tlsFailures.increment();
                            });
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        counters.activeTransports.increment();
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        counters.activeTransports.decrement();
        ctx.fireChannelInactive();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        ctx.write(
                message,
                promise.unvoid()
                        .addListener(
                                f -> {
                                    if (!f.isSuccess()) counters.writeFailures.increment();
                                }));
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (!ctx.channel().isWritable()) counters.nonWritableTransitions.increment();
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                || event
                        == WebSocketClientProtocolHandler.ClientHandshakeStateEvent
                                .HANDSHAKE_COMPLETE) counters.websocketHandshakes.increment();
        if (event == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT
                || event
                        == WebSocketClientProtocolHandler.ClientHandshakeStateEvent
                                .HANDSHAKE_TIMEOUT) counters.handshakeTimeouts.increment();
        if (event instanceof IdleStateEvent) counters.idleEvents.increment();
        ctx.fireUserEventTriggered(event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) {
        counters.exceptions.increment();
        ctx.fireExceptionCaught(failure);
    }
}
