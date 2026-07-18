package cn.managame.rpc;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 客户端内部控制 handler（位于业务 handler 之前）：处理握手确认、心跳、空闲。
 * <ul>
 *   <li>握手确认：协商完成，把连接挂入 peer 开始参与路由；</li>
 *   <li>写空闲（{@code heartbeatIntervalSeconds} 内没发任何字节）：发心跳 ping 保活；</li>
 *   <li>收到 pong：仅作为活跃信号（被 IdleStateHandler 计入读活跃），消费掉；</li>
 *   <li>读空闲（{@code idleTimeoutSeconds} 内没收到任何字节）：判定连接已死，关闭以触发重连。</li>
 * </ul>
 * 其余消息透传给业务 handler。
 */
@ChannelHandler.Sharable
public class RpcClientInternalHandler extends ChannelInboundHandlerAdapter {

    private final RpcClient client;

    public RpcClientInternalHandler(RpcClient client) {
        this.client = client;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RpcRequest request) {
            switch (request.getCommand()) {
                case RpcInternal.CMD_HANDSHAKE_ACK -> {
                    client.onHandshakeAck(ctx.channel());
                    return;
                }
                case RpcInternal.CMD_HEARTBEAT_PONG -> {
                    return; // 活跃信号，消费
                }
                default -> {
                    // 业务消息（含服务端推送），透传
                }
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(RpcRequest.oneway(RpcInternal.CMD_HEARTBEAT_PING));
                return;
            }
            if (event.state() == IdleState.READER_IDLE) {
                ctx.close(); // 对端无响应，关闭触发重连
                return;
            }
        }
        ctx.fireUserEventTriggered(evt);
    }
}
