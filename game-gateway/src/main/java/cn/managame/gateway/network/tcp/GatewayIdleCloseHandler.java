package cn.managame.gateway.network.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读空闲关连接：配合 {@code IdleStateHandler}，客户端心跳断供（读空闲超时）时
 * 直接关闭，交给 {@code GatewayNetworkHandler#onDisconnect} 走统一解绑。
 * 不推踢下线帧——链路已不健康，写大概率也发不出去。
 */
public class GatewayIdleCloseHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(GatewayIdleCloseHandler.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            logger.info("connection idle, closing {}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
