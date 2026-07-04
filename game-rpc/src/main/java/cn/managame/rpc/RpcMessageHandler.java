package cn.managame.rpc;

import cn.managame.rpc.connection.RpcConnection;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public abstract class RpcMessageHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RpcMessageHandler.class);

    /** 业务 handler 抛异常时回给请求方的错误码 */
    public static final int CODE_SERVER_ERROR = 500;

    private volatile RpcContainer rpcContainer;

    final void bind(RpcContainer rpcContainer) {
        if (this.rpcContainer != null) {
            throw new IllegalStateException("handler already bound to an endpoint, create one instance per endpoint");
        }
        this.rpcContainer = rpcContainer;
    }

    /**
     * 业务消息回调（IO 线程，body 为 byte[]，按 serialType 自行还原）。
     * {@code msg.isOneway()} 区分是否需要回包，需要时 {@code connection.writeMsg(RpcResponse.of(...))}。
     */
    protected abstract void handleUserMsg(RpcConnection rpcConnection, RpcRequest msg);

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RpcResponse response) {
            RpcConnection connection = rpcContainer.getRpcConnection(ctx.channel());
            if (connection == null) {
                rpcContainer.getMetrics().onLateResponse();
                return;
            }
            rpcContainer.onResponse(connection, response);
            return;
        }
        if (msg instanceof RpcRequest request) {
            if (request.isInternal()) {
                return; // 心跳/握手已由前置 handler 消费，这里兜底吞掉
            }
            RpcConnection connection = rpcContainer.getRpcConnection(ctx.channel());
            if (connection == null) {
                // 连接已被摘除（disconnect/断线竞争窗口），消息无处安放，丢弃
                log.debug("rpc msg on unregistered connection dropped, remote={}", ctx.channel().remoteAddress());
                return;
            }
            try {
                handleUserMsg(connection, request);
            } catch (Throwable t) {
                // 使用方 handler 异常不传播到 pipeline，否则会触发 exceptionCaught 误断连
                log.warn("rpc user msg handler threw, command={}, remote={}",
                        request.getCommand(), ctx.channel().remoteAddress(), t);
                // 请求类消息：回错误响应让请求方 fast-fail，而不是干等到超时（oneway 无需回包）
                if (!request.isOneway()) {
                    connection.writeMsg(RpcResponse.error(request.getRequestId(), CODE_SERVER_ERROR,
                            "server handler error: " + t.getMessage()));
                }
            }
            return;
        }
        ReferenceCountUtil.release(msg);
    }

    @Override
    public final void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        rpcContainer.onChannelActive(ctx.channel());
    }

    @Override
    public final void channelInactive(ChannelHandlerContext ctx) throws Exception {
        rpcContainer.removeChannel(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("rpc channel error, remote={}, closing", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
