package cn.managame.example.aiservice;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 极简 OpenAI 兼容本地桩服务（{@code POST /v1/chat/completions}），支持标准 JSON 回答、OpenAI {@code tool_calls}
 * 以及 SSE {@code chat.completion.chunk} 流式输出，使集成测试在无真实 API Key 时也能真实走完 Spring AI 全链路。
 */
final class FakeOpenAiServer implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventLoopGroup group;
    private final Channel channel;
    private final int port;

    FakeOpenAiServer() {
        this.group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024),
                                new SimpleChannelInboundHandler<FullHttpRequest>() {
                                    @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                        String body = req.content().toString(StandardCharsets.UTF_8);
                                        Thread.ofVirtual().start(() -> handle(ctx, body));
                                    }
                                });
                    }
                });
        this.channel = bootstrap.bind("127.0.0.1", 0).syncUninterruptibly().channel();
        this.port = ((InetSocketAddress) channel.localAddress()).getPort();
    }

    int port() { return port; }

    private void handle(ChannelHandlerContext ctx, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            boolean stream = root.path("stream").asBoolean(false);
            JsonNode messages = root.path("messages");
            JsonNode last = messages.get(messages.size() - 1);
            String lastRole = last.path("role").asString("");
            String lastContent = last.path("content").asString("");

            int userTurns = 0;
            StringBuilder history = new StringBuilder();
            for (JsonNode msg : messages) {
                if ("user".equals(msg.path("role").asString(""))) {
                    userTurns++;
                    if (history.length() > 0) history.append('|');
                    history.append(msg.path("content").asString(""));
                }
            }

            if (stream) {
                boolean slow = lastContent.contains("slow stream");
                sendStream(ctx, slow);
                return;
            }

            // 工具调用回传结果 -> 生成最终自然语言回复
            if ("tool".equals(lastRole)) {
                sendJson(ctx, textCompletion("TOOL_RESULT:" + lastContent));
                return;
            }

            // NPC / GM 触发 MCP 工具调用的场景
            if (lastContent.contains("check bag")) {
                sendJson(ctx, toolCallCompletion("query_bag", "{\"roleId\":1001}"));
                return;
            }
            if (lastContent.contains("steal bag 1002")) {
                sendJson(ctx, toolCallCompletion("query_bag", "{\"roleId\":1002}"));
                return;
            }
            if (lastContent.contains("grant 50 gold")) {
                sendJson(ctx, toolCallCompletion("grant_item", "{\"roleId\":1001,\"itemId\":0,\"count\":50}"));
                return;
            }
            if (lastContent.contains("grant 999 gold")) {
                sendJson(ctx, toolCallCompletion("grant_item", "{\"roleId\":1001,\"itemId\":0,\"count\":999}"));
                return;
            }
            if (lastContent.contains("online count")) {
                sendJson(ctx, toolCallCompletion("online_count", "{}"));
                return;
            }

            // 结构化选项决策场景
            if (lastContent.contains("slow-decide")) {
                Thread.sleep(350);
                sendJson(ctx, textCompletion("{\"action\":\"TRADE\",\"reason\":\"late\"}"));
                return;
            }
            if (lastContent.contains("merchant-offer")) {
                sendJson(ctx, textCompletion("{\"action\":\"TRADE\",\"reason\":\"good deal\"}"));
                return;
            }
            if (lastContent.contains("bad-choice")) {
                sendJson(ctx, textCompletion("{\"action\":\"ATTACK\",\"reason\":\"disallowed\"}"));
                return;
            }

            // 普通多轮对话：返回本会话累计轮数与历史，方便测试验证会话隔离与重置
            sendJson(ctx, textCompletion("turns=" + userTurns + ";history=" + history));
        } catch (Exception e) {
            FullHttpResponse err = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer(String.valueOf(e.getMessage()), StandardCharsets.UTF_8));
            err.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, err.content().readableBytes());
            ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(err));
        }
    }

    private void sendStream(ChannelHandlerContext ctx, boolean slow) throws InterruptedException {
        List<String> tokens = List.of("欢迎", "来到", "铁匠铺");
        ctx.channel().eventLoop().execute(() -> {
            DefaultHttpResponse headers = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            headers.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
            headers.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            ctx.writeAndFlush(headers);
        });
        for (int i = 0; i < tokens.size(); i++) {
            if (slow && i > 0) Thread.sleep(1500);
            if (!ctx.channel().isActive()) return;
            String chunkJson = mapper.writeValueAsString(Map.of(
                    "id", "chatcmpl-stream",
                    "object", "chat.completion.chunk",
                    "created", 1_700_000_000L,
                    "model", "demo-model",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", i == 0 ? Map.of("role", "assistant", "content", tokens.get(i))
                                    : Map.of("content", tokens.get(i))
                    ))
            ));
            String sse = "data: " + chunkJson + "\n\n";
            ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(
                    new DefaultHttpContent(Unpooled.copiedBuffer(sse, StandardCharsets.UTF_8))));
        }
        String finishChunk = mapper.writeValueAsString(Map.of(
                "id", "chatcmpl-stream",
                "object", "chat.completion.chunk",
                "created", 1_700_000_000L,
                "model", "demo-model",
                "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop"))
        ));
        String tail = "data: " + finishChunk + "\n\ndata: [DONE]\n\n";
        ctx.channel().eventLoop().execute(() -> {
            ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(tail, StandardCharsets.UTF_8)));
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        });
    }

    private String textCompletion(String content) {
        return mapper.writeValueAsString(Map.of(
                "id", "chatcmpl-text",
                "object", "chat.completion",
                "created", 1_700_000_000L,
                "model", "demo-model",
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", content),
                        "finish_reason", "stop"
                )),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)
        ));
    }

    private String toolCallCompletion(String toolName, String argumentsJson) {
        return mapper.writeValueAsString(Map.of(
                "id", "chatcmpl-tool",
                "object", "chat.completion",
                "created", 1_700_000_000L,
                "model", "demo-model",
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of(
                                "role", "assistant",
                                "tool_calls", List.of(Map.of(
                                        "id", "call_1",
                                        "type", "function",
                                        "function", Map.of("name", toolName, "arguments", argumentsJson)
                                ))
                        ),
                        "finish_reason", "tool_calls"
                )),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)
        ));
    }

    private static void sendJson(ChannelHandlerContext ctx, String json) {
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
        ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(res));
    }

    @Override public void close() {
        channel.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
