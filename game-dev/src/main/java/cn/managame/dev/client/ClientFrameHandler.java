package cn.managame.dev.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 把解码出的 {@link ClientResponse} 投递给上层回调。
 *
 * <p>回调在 Netty IO 线程上执行，回调实现应尽量轻量（打印/转交队列），不要在此做阻塞调用。
 * 这与服务端“rpc 回调在 IO 线程、宿主自行投递到线程组”的约定一致。</p>
 */
public class ClientFrameHandler extends SimpleChannelInboundHandler<ClientResponse> {

    private static final Logger logger = LoggerFactory.getLogger(ClientFrameHandler.class);

    private final Consumer<ClientResponse> onResponse;

    public ClientFrameHandler(Consumer<ClientResponse> onResponse) {
        this.onResponse = onResponse;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ClientResponse msg) {
        onResponse.accept(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("connection closed by server: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("client pipeline exception, closing connection", cause);
        ctx.close();
    }
}
