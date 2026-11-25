package cn.managame.benchmark.ai;

import cn.managame.ai.AiClient;
import cn.managame.ai.AiResult;
import cn.managame.ai.mcp.McpHttpHandler;
import cn.managame.ai.mcp.McpTools;
import cn.managame.ai.spi.McpToolExecutor;
import cn.managame.benchmark.ai.ToolInvokeBenchmark.BagTools;
import cn.managame.benchmark.ai.ToolInvokeBenchmark.JacksonCodec;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * End-to-end loopback HTTP throughput of {@link AiClient#call}, {@link AiClient#stream}, and {@link McpHttpHandler}
 * serving JSON-RPC 2.0 {@code tools/call} requests.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class AiHttpBenchmark {
    public record ChatReq(long npcId, String text) {}
    public record ChatRes(String reply) {}

    private static final byte[] CHAT_RESPONSE = "{\"reply\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SSE_RESPONSE = (
            "id: 1\nevent: token\ndata: 你好\n\n"
            + "id: 2\nevent: token\ndata: 冒险者\n\n"
            + "id: 3\nevent: done\ndata: [DONE]\n\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final String MCP_TOOLS_CALL = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"query_bag\",\"arguments\":{\"roleId\":1001,\"itemId\":2001}}}";

    private NetworkServer echoAiServer;
    private NetworkServer mcpServer;
    private McpTools mcpTools;
    private AiClient aiClient;
    private AiClient mcpClient;
    private final ChatReq sampleReq = new ChatReq(7, "hello");

    @Setup(Level.Trial) public void start() {
        JacksonCodec codec = new JacksonCodec();
        echoAiServer = NetworkServer.start(0, HttpInitializer.http().handler(exchange -> {
            if ("/stream".equals(exchange.path())) {
                FullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(SSE_RESPONSE));
                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
                exchange.respond(resp);
            } else {
                FullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(CHAT_RESPONSE));
                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                exchange.respond(resp);
            }
        }).build());

        mcpTools = McpTools.builder()
                .codec(codec)
                .executor(McpToolExecutor.direct())
                .register(List.of(new BagTools()))
                .build();
        mcpServer = NetworkServer.start(0,
                HttpInitializer.http().contextPath("/mcp").handler(new McpHttpHandler(mcpTools)).build());

        aiClient = AiClient.builder("http://127.0.0.1:" + echoAiServer.port())
                .codec(codec)
                .build();
        mcpClient = AiClient.builder("http://127.0.0.1:" + mcpServer.port() + "/mcp")
                .build();
    }

    @TearDown(Level.Trial) public void stop() {
        aiClient.close();
        mcpClient.close();
        mcpServer.close();
        mcpTools.close();
        echoAiServer.close();
    }

    @Benchmark public AiResult<ChatRes> clientUnaryCall() throws Exception {
        CompletableFuture<AiResult<ChatRes>> f = new CompletableFuture<>();
        aiClient.call("/chat", sampleReq, ChatRes.class, f::complete);
        AiResult<ChatRes> res = f.get();
        if (!res.isSuccess()) throw new IllegalStateException("Call failed: " + res);
        return res;
    }

    @Benchmark public AiResult<Void> clientSseStream(Blackhole bh) throws Exception {
        CompletableFuture<AiResult<Void>> f = new CompletableFuture<>();
        aiClient.stream("/stream", sampleReq, bh::consume, f::complete);
        AiResult<Void> res = f.get();
        if (!res.isSuccess()) throw new IllegalStateException("Stream failed: " + res);
        return res;
    }

    @Benchmark public AiResult<String> mcpToolsCall() throws Exception {
        CompletableFuture<AiResult<String>> f = new CompletableFuture<>();
        mcpClient.call("", MCP_TOOLS_CALL, String.class, f::complete);
        AiResult<String> res = f.get();
        if (!res.isSuccess()) throw new IllegalStateException("MCP call failed: " + res);
        return res;
    }
}
