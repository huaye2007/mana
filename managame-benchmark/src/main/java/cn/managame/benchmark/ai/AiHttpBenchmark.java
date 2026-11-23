package cn.managame.benchmark.ai;

import cn.managame.ai.AiClient;
import cn.managame.ai.AiResult;
import cn.managame.ai.AiTools;
import cn.managame.ai.http.AiToolHttpHandler;
import cn.managame.ai.spi.AiToolExecutor;
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

/**
 * End-to-end loopback HTTP throughput of {@link AiClient#call} against a Netty HTTP server and of
 * {@link AiToolHttpHandler} serving {@code POST /tools/{name}} requests.
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
    private static final byte[] TOOL_ARGS = "{\"roleId\":1001,\"itemId\":2001}".getBytes(StandardCharsets.UTF_8);

    private NetworkServer echoAiServer;
    private NetworkServer toolServer;
    private AiClient aiClient;
    private AiClient toolClient;
    private final ChatReq sampleReq = new ChatReq(7, "hello");

    @Setup(Level.Trial) public void start() {
        JacksonCodec codec = new JacksonCodec();
        echoAiServer = NetworkServer.start(0, HttpInitializer.http().handler(exchange -> {
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(CHAT_RESPONSE));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            exchange.respond(resp);
        }).build());

        AiTools tools = AiTools.builder()
                .codec(codec)
                .executor(AiToolExecutor.direct())
                .register(List.of(new BagTools()))
                .build();
        toolServer = NetworkServer.start(0,
                HttpInitializer.http().contextPath("/ai").handler(new AiToolHttpHandler(tools)).build());

        aiClient = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + echoAiServer.port())
                .codec(codec)
                .build();
        toolClient = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + toolServer.port() + "/ai")
                .build();
    }

    @TearDown(Level.Trial) public void stop() {
        aiClient.close();
        toolClient.close();
        toolServer.close();
        echoAiServer.close();
    }

    @Benchmark public AiResult<ChatRes> clientUnaryCall() throws Exception {
        CompletableFuture<AiResult<ChatRes>> f = new CompletableFuture<>();
        aiClient.call("/chat", sampleReq, ChatRes.class, f::complete);
        AiResult<ChatRes> res = f.get();
        if (!res.isSuccess()) throw new IllegalStateException("Call failed: " + res);
        return res;
    }

    @Benchmark public AiResult<String> httpToolCall() throws Exception {
        CompletableFuture<AiResult<String>> f = new CompletableFuture<>();
        toolClient.call("/tools/query_bag", TOOL_ARGS, String.class, f::complete);
        AiResult<String> res = f.get();
        if (!res.isSuccess()) throw new IllegalStateException("Tool call failed: " + res);
        return res;
    }
}
