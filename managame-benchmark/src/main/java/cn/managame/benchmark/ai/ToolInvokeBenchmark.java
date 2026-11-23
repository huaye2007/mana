package cn.managame.benchmark.ai;

import cn.managame.ai.AiJsonCodec;
import cn.managame.ai.AiToolInterceptor;
import cn.managame.ai.AiToolResult;
import cn.managame.ai.AiTools;
import cn.managame.ai.annotation.AiTool;
import cn.managame.ai.spi.AiToolExecutor;
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
 * Measures {@link AiTools#invoke} overhead on its direct executor: argument decoding, MethodHandle dispatch,
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
        @AiTool(name = "query_bag", description = "Query item count", group = "npc", readOnly = true)
        public BagCount queryBag(QueryBag req) {
            return new BagCount(req.roleId(), req.itemId(), 5);
        }

        @AiTool(name = "online_count", description = "Online count", group = "gm", readOnly = true)
        public int onlineCount() { return 1000; }
    }

    static final class JacksonCodec implements AiJsonCodec {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
        @Override public Object decode(String json, Type type) throws Exception { return mapper.readValue(json, mapper.constructType(type)); }
    }

    private static final String ARGS_JSON = "{\"roleId\":1001,\"itemId\":2001}";
    private AiTools plain;
    private AiTools intercepted;
    private AiTools metered;

    @Setup(Level.Trial) public void setup() {
        AiJsonCodec codec = new JacksonCodec();
        plain = AiTools.builder()
                .codec(codec)
                .executor(AiToolExecutor.direct())
                .register(List.of(new BagTools()))
                .build();
        intercepted = AiTools.builder()
                .codec(codec)
                .executor(AiToolExecutor.direct())
                .intercept(List.of(new AiToolInterceptor() {}))
                .register(List.of(new BagTools()))
                .build();
        metered = AiTools.builder()
                .codec(codec)
                .executor(AiToolExecutor.direct())
                .metrics(new CountingMetrics())
                .register(List.of(new BagTools()))
                .build();
    }

    @Benchmark public AiToolResult invokeNoArg() {
        AiToolResult[] box = new AiToolResult[1];
        plain.invoke("online_count", "{}", r -> box[0] = r);
        return box[0];
    }

    @Benchmark public AiToolResult invokeRecord() {
        AiToolResult[] box = new AiToolResult[1];
        plain.invoke("query_bag", ARGS_JSON, r -> box[0] = r);
        return box[0];
    }

    @Benchmark public AiToolResult invokeIntercepted() {
        AiToolResult[] box = new AiToolResult[1];
        intercepted.invoke("query_bag", ARGS_JSON, r -> box[0] = r);
        return box[0];
    }

    @Benchmark public AiToolResult invokeMetered() {
        AiToolResult[] box = new AiToolResult[1];
        metered.invoke("query_bag", ARGS_JSON, r -> box[0] = r);
        return box[0];
    }
}
