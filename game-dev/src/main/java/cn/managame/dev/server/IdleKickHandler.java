package cn.managame.dev.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空闲连接踢除：配合 {@code IdleStateHandler} 使用，读空闲超时（客户端心跳断供）
 * 直接关连接，交给 {@code GameHandler#onDisconnect} 走统一的解绑清理。
 *
 * <p>不推踢下线帧：读空闲通常意味着链路已经不健康（客户端假死/网络断），
 * 写出去大概率也到不了，直接关闭最干净。</p>
 */
public class IdleKickHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(IdleKickHandler.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            logger.info("connection idle, kick {}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }
}
