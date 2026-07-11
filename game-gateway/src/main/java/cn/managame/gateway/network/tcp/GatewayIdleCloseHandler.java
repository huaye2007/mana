package cn.managame.gateway.network.tcp;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;

@ChannelHandler.Sharable
public final class GatewayIdleCloseHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent idle && idle.isFirst()) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, event);
    }
}
