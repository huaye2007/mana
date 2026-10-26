package cn.managame.network.http;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Keeps responses in request order, which HTTP/1.1 requires even when requests are answered asynchronously or
 * pipelined: once a request has been read completely, reading stops, and the FlowControlHandler before this one
 * holds any further input until the final response to that request has been written. Interim 1xx responses,
 * such as 100 Continue, do not end an exchange.
 */
final class HttpExchangeGate extends ChannelDuplexHandler {
    /** Whether the response being written is final, so that its last part ends the exchange. */
    private boolean finalResponse;

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof LastHttpContent) ctx.channel().config().setAutoRead(false);
        ctx.fireChannelRead(message);
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (message instanceof HttpResponse response)
            finalResponse = response.status().codeClass() != HttpStatusClass.INFORMATIONAL;
        if (finalResponse && message instanceof LastHttpContent) {
            finalResponse = false;
            promise = promise.unvoid();
            promise.addListener(written -> {
                if (written.isSuccess()) ctx.channel().config().setAutoRead(true);
            });
        }
        ctx.write(message, promise);
    }
}
