package cn.managame.example.ai;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * 挂在 {@code HttpInitializer.addLast} 上的 Netty Handler：在请求到达 {@code McpHttpHandler} 之前校验
 * {@code Authorization: Bearer <token>}，防止 MCP 工具端点裸奔。
 */
@ChannelHandler.Sharable
public final class BearerTokenAuthHandler extends ChannelInboundHandlerAdapter {
    private final byte[] expectedHeader;

    public BearerTokenAuthHandler(String token) {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) throw new IllegalArgumentException("token must not be blank");
        this.expectedHeader = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest req)) {
            ctx.fireChannelRead(msg);
            return;
        }
        String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth != null && MessageDigest.isEqual(expectedHeader, auth.getBytes(StandardCharsets.UTF_8))) {
            ctx.fireChannelRead(msg);
            return;
        }
        ReferenceCountUtil.release(req);
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED,
                Unpooled.copiedBuffer("{\"error\":\"unauthorized\"}", StandardCharsets.UTF_8));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }
}
