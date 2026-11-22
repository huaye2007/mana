package cn.managame.benchmark.ai;

import cn.managame.ai.*;
import java.util.List;
import java.util.concurrent.*;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class AiBenchmark {
    public record Equipment(long role, int level) {}
    private final Equipment input = new Equipment(317, 40);
    private final AiJson json = AiJson.jackson();
    private final AiTools tools = AiTools.builder().register(List.of(this)).build();
    private final String arguments = json.encode(input);
    private final AiTools.Entry<Void> entry = new AiTools.Entry<>() {
        public CompletionStage<?> dispatch(Void caller, AiTools.Tool tool, Object message) { return CompletableFuture.completedFuture(message); }
        public CompletionStage<?> propose(Void caller, AiTools.Tool tool, Object message) { throw new AssertionError(); }
    };

    @AiTool(name = "equipment", description = "Inspect equipment")
    public void equipment(Equipment request) { throw new AssertionError(); }

    @Benchmark public String encodeEvent() { return json.encode(input); }
    @Benchmark public CompletionStage<?> decodeToolCall() { return tools.call("equipment", arguments, null, entry); }
}
