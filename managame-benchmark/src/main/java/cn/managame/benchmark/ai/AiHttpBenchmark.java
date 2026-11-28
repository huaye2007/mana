package cn.managame.benchmark.ai;

import cn.managame.ai.AiClient;
import cn.managame.ai.AiResult;
import cn.managame.ai.mcp.McpHttpHandler;
import cn.managame.ai.mcp.McpTools;
import cn.managame.ai.spi.McpToolExecutor;
import cn.managame.benchmark.CountingSocketChannel;
import cn.managame.benchmark.PeerProcess;
import cn.managame.benchmark.ai.ToolInvokeBenchmark.BagTools;
import cn.managame.benchmark.ai.ToolInvokeBenchmark.JacksonCodec;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
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
 * serving JSON-RPC 2.0 {@code tools/call} requests, dialing through {@link CountingSocketChannel} so wire bytes are
 * recorded; {@link AiHttpProcessBenchmark} runs the server side in a separate {@link PeerProcess}.
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

    static final byte[] CHAT_RESPONSE = "{\"reply\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] SSE_RESPONSE = (
            "id: 1\nevent: token\ndata: 你好\n\n"
            + "id: 2\nevent: token\ndata: 冒险者\n\n"
            + "id: 3\nevent: done\ndata: [DONE]\n\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final String MCP_TOOLS_CALL = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"query_bag\",\"arguments\":{\"roleId\":1001,\"itemId\":2001}}}";

    private ServerFixture localServer;
    private PeerProcess peer;
    private EventLoopGroup clientGroup;
    private AiClient aiClient;
    private AiClient mcpClient;
    private final ChatReq sampleReq = new ChatReq(7, "hello");

    @Setup(Level.Trial) public void start() throws Exception {
        JacksonCodec codec = new JacksonCodec();
        int port;
        if (otherProcess()) {
            peer = PeerProcess.start(AiHttpPeer.class);
            port = peer.port();
        } else {
            localServer = startServer();
            port = localServer.port();
        }
        clientGroup = CountingSocketChannel.newGroup("ai-bench-client");
        Bootstrap bootstrap = new Bootstrap().group(clientGroup).channel(CountingSocketChannel.class);
        aiClient = AiClient.builder("http://127.0.0.1:" + port)
                .codec(codec)
                .bootstrap(bootstrap)
                .build();
        mcpClient = AiClient.builder("http://127.0.0.1:" + port + "/mcp")
                .bootstrap(bootstrap)
                .build();
    }

    @TearDown(Level.Trial) public void stop() {
        aiClient.close();
        mcpClient.close();
        clientGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        if (localServer != null) localServer.close();
        if (peer != null) peer.close();
    }

    protected boolean otherProcess() { return false; }

    static ServerFixture startServer() {
        JacksonCodec codec = new JacksonCodec();
        McpTools mcpTools = McpTools.builder()
                .codec(codec)
                .executor(McpToolExecutor.direct())
                .register(List.of(new BagTools()))
                .build();
        McpHttpHandler mcpHandler = McpHttpHandler.builder(mcpTools).path("/mcp").build();
        NetworkServer server = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().handler(exchange -> {
                    switch (exchange.path()) {
                        case "/mcp" -> mcpHandler.handle(exchange);
                        case "/stream" -> {
                            FullHttpResponse resp = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(SSE_RESPONSE));
                            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
                            exchange.respond(resp);
                        }
                        default -> {
                            FullHttpResponse resp = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(CHAT_RESPONSE));
                            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                            exchange.respond(resp);
                        }
                    }
                }).build());
        return new ServerFixture(server, mcpTools);
    }

    record ServerFixture(NetworkServer server, McpTools mcpTools) implements AutoCloseable {
        int port() { return server.port(); }
        @Override public void close() {
            server.close();
            mcpTools.close();
        }
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
