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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 挂在 {@code HttpInitializer.addLast} 上的 Netty Handler：在请求到达 {@code McpHttpHandler} 之前校验
 * {@code Authorization: Bearer <token>}：
 * <ul>
 *   <li>支持逗号分隔的多 Token 并存窗口（{@code "new-token,old-token"}），配合 {@link #rotateTokens} 零停机热轮换密钥</li>
 *   <li>支持为 {@code X-Caller: gm} 单独配置高权限 GM Token（未单独配置时复用默认 Token），防止低权限凭证伪造 GM 身份</li>
 * </ul>
 */
@ChannelHandler.Sharable
public final class BearerTokenAuthHandler extends ChannelInboundHandlerAdapter {
    private final AtomicReference<TokenSet> tokens;

    public BearerTokenAuthHandler(String npcTokens) {
        this(npcTokens, null);
    }

    public BearerTokenAuthHandler(String npcTokens, String gmTokens) {
        this.tokens = new AtomicReference<>(parseTokenSet(npcTokens, gmTokens));
    }

    /** 热更新允许的 Bearer Token 集合（例如密钥轮换过渡期同时放行新旧两个 Token，切换完成后摘掉旧 Token）。 */
    public void rotateTokens(String npcTokens, String gmTokens) {
        this.tokens.set(parseTokenSet(npcTokens, gmTokens));
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest req)) {
            ctx.fireChannelRead(msg);
            return;
        }
        String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        String caller = req.headers().get("X-Caller");
        TokenSet current = tokens.get();
        List<byte[]> allowed = "gm".equalsIgnoreCase(caller) ? current.gmHeaders : current.allHeaders;
        if (auth != null && matchesAny(allowed, auth.getBytes(StandardCharsets.UTF_8))) {
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

    private static boolean matchesAny(List<byte[]> expectedList, byte[] provided) {
        boolean matched = false;
        for (byte[] expected : expectedList) {
            // 遍历全部候选以保持常数时间比较语义
            matched |= MessageDigest.isEqual(expected, provided);
        }
        return matched;
    }

    private static TokenSet parseTokenSet(String npcTokens, String gmTokens) {
        List<byte[]> npc = parseHeaders(npcTokens);
        List<byte[]> gm = (gmTokens == null || gmTokens.isBlank()) ? npc : parseHeaders(gmTokens);
        List<byte[]> all = new ArrayList<>(npc.size() + gm.size());
        all.addAll(npc);
        if (gm != npc) all.addAll(gm);
        return new TokenSet(List.copyOf(all), List.copyOf(gm));
    }

    private static List<byte[]> parseHeaders(String csv) {
        Objects.requireNonNull(csv, "tokens");
        List<byte[]> list = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(("Bearer " + trimmed).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (list.isEmpty()) throw new IllegalArgumentException("At least one non-blank token is required");
        return list;
    }

    private record TokenSet(List<byte[]> allHeaders, List<byte[]> gmHeaders) {}
}
