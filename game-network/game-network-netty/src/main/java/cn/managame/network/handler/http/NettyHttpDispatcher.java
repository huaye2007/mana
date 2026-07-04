package cn.managame.network.handler.http;

import cn.managame.network.http.IHttpHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;

public class NettyHttpDispatcher extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final IHttpHandler httpHandler;
    private final String protocol;

    public NettyHttpDispatcher(IHttpHandler httpHandler, String protocol) {
        this.httpHandler = httpHandler;
        this.protocol = protocol;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        NettyHttpExchange exchange = new NettyHttpExchange(ctx, request, protocol);
        try {
            httpHandler.onRequest(exchange);
            if(!exchange.isResponseWritten()){
                exchange.writeResponse(204, new byte[0]);
            }
        } catch (Exception e) {
            httpHandler.onException(exchange, e);
            if(!exchange.isResponseWritten()){
                exchange.writeResponse(500, new byte[0]);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
