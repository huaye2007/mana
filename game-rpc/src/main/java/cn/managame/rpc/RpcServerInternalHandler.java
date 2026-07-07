package cn.managame.rpc;

import cn.managame.common.context.MetadataKeys;
import cn.managame.rpc.connection.RpcConnection;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端内部控制 handler（位于业务 handler 之前）：处理握手、心跳、读空闲。
 * <ul>
 *   <li>握手请求：校验 token、登记对端身份并挂入 peer，回执握手确认；</li>
 *   <li>心跳 ping：回 pong；</li>
 *   <li>读空闲（{@code idleTimeoutSeconds} 内没收到任何字节）：判定对端已死，关闭连接。</li>
 * </ul>
 * 其余消息透传给业务 handler。
 */
@ChannelHandler.Sharable
public class RpcServerInternalHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RpcServerInternalHandler.class);

    private final RpcServer server;

    public RpcServerInternalHandler(RpcServer server) {
        this.server = server;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RpcRequest request) {
            switch (request.getCommand()) {
                case RpcInternal.CMD_HANDSHAKE -> {
                    handshake(ctx, request);
                    return;
                }
                case RpcInternal.CMD_HEARTBEAT_PING -> {
                    ctx.writeAndFlush(RpcRequest.oneway(RpcInternal.CMD_HEARTBEAT_PONG));
                    return;
                }
                default -> {
                    // 业务消息，透传
                }
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent event && event.state() == IdleState.READER_IDLE) {
            log.debug("rpc server read idle, closing dead connection: {}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    private void handshake(ChannelHandlerContext ctx, RpcRequest request) {
        RpcConnection connection = server.getRpcConnection(ctx.channel());
        if (connection == null) {
            log.warn("rpc handshake on unregistered connection, remote={}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        String token = request.metaString(MetadataKeys.RPC_AUTH_TOKEN);
        if (server.getAuthToken() != null && !server.getAuthToken().equals(token)) {
            // 校验失败直接断连、不回执，客户端那条连接永远不会挂入 peer
            log.warn("rpc handshake rejected: bad token, remote={}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        String serviceName = request.metaString(MetadataKeys.RPC_SERVICE_NAME);
        String serviceId = request.metaString(MetadataKeys.RPC_SERVICE_ID);
        if (serviceName != null && !serviceName.isEmpty()) {
            // 对端带了身份：登记到服务端 peer，使服务端可反向 invoke 该客户端
            connection.setServiceName(serviceName);
            connection.setServiceId(serviceId);
            server.getOrCreateRpcPeer(serviceName, serviceId).add(connection);
        }
        // 回执握手确认，客户端收到后才把它那条连接挂入 peer
        connection.writeMsg(RpcRequest.oneway(RpcInternal.CMD_HANDSHAKE_ACK));
        log.info("rpc handshake ok: {}/{} <- {}", serviceName, serviceId, ctx.channel().remoteAddress());
    }
}
