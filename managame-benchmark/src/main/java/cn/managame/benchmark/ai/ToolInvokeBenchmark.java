package cn.managame.benchmark.ai;

import cn.managame.ai.AiCodec;
import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.annotation.McpTool;
import cn.managame.ai.mcp.McpToolInterceptor;
import cn.managame.ai.mcp.McpToolResult;
import cn.managame.ai.mcp.McpTools;
import cn.managame.ai.spi.McpToolExecutor;
import cn.managame.benchmark.CountingMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.List;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures {@link McpTools#call} overhead on its direct executor: argument decoding, MethodHandle dispatch,
 * interceptor chaining, and metrics recording.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ToolInvokeBenchmark {
    public record QueryBag(long roleId, int itemId) {}
    public record BagCount(long roleId, int itemId, int count) {}

    public static class BagTools {
        @McpTool(name = "query_bag", description = "Query item count via DTO", group = "npc", readOnly = true)
        public BagCount queryBag(QueryBag req) {
            return new BagCount(req.roleId(), req.itemId(), 5);
        }

        @McpTool(name = "query_item", description = "Query item count via params", group = "npc", readOnly = true)
        public BagCount queryItem(@McpParam(name = "roleId") long roleId, @McpParam(name = "itemId") int itemId) {
            return new BagCount(roleId, itemId, 5);
        }

        @McpTool(name = "online_count", description = "Online count", group = "gm", readOnly = true)
        public int onlineCount() { return 1000; }
    }

    static final class JacksonCodec implements AiCodec {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
        @Override public Object decode(String json, Type type) throws Exception {
            return mapper.readValue(json, mapper.constructType(type));
        }
    }

    private static final String ARGS_JSON = "{\"roleId\":1001,\"itemId\":2001}";
    private McpTools plain;
    private McpTools intercepted;
    private McpTools metered;

    @Setup(Level.Trial) public void setup() {
        AiCodec codec = new JacksonCodec();
        plain = McpTools.builder()
                .codec(codec)
                .executor(McpToolExecutor.direct())
                .register(List.of(new BagTools()))
                .build();
        intercepted = McpTools.builder()
                .codec(codec)
                .executor(McpToolExecutor.direct())
                .intercept(List.of(new McpToolInterceptor() {}))
                .register(List.of(new BagTools()))
                .build();
        metered = McpTools.builder()
                .codec(codec)
                .executor(McpToolExecutor.direct())
                .metrics(new CountingMetrics())
                .register(List.of(new BagTools()))
                .build();
    }

    @Benchmark public McpToolResult invokeNoArg() {
        McpToolResult[] box = new McpToolResult[1];
        plain.call("online_count", "{}", r -> box[0] = r);
        return box[0];
    }

    @Benchmark public McpToolResult invokeParams() {
        McpToolResult[] box = new McpToolResult[1];
        plain.call("query_item", ARGS_JSON, r -> box[0] = r);
        return box[0];
    }

    @Benchmark public McpToolResult invokeRecord() {
        McpToolResult[] box = new McpToolResult[1];
        plain.call("query_bag", ARGS_JSON, r -> box[0] = r);
        return box[0];
    }

    @Benchmark public McpToolResult invokeIntercepted() {
        McpToolResult[] box = new McpToolResult[1];
        intercepted.call("query_bag", ARGS_JSON, r -> box[0] = r);
        return box[0];
    }

    @Benchmark public McpToolResult invokeMetered() {
        McpToolResult[] box = new McpToolResult[1];
        metered.call("query_bag", ARGS_JSON, r -> box[0] = r);
        return box[0];
    }
}
