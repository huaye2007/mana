package cn.managame.benchmark.ai;

import cn.managame.ai.internal.SseParser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Measures incremental W3C SSE parsing throughput and allocation cost for a typical streamed LLM reply. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class SseParserBenchmark {
    private static final byte[] TEN_EVENTS = (
            "id: 1\nevent: token\ndata: 欢迎来到\n\n"
            + "id: 2\nevent: token\ndata: 铁匠铺，\n\n"
            + "id: 3\nevent: token\ndata: 冒险者！\n\n"
            + "id: 4\nevent: token\ndata: 你背包里\n\n"
            + "id: 5\nevent: token\ndata: 有500金币，\n\n"
            + "id: 6\nevent: token\ndata: 需要打造\n\n"
            + "id: 7\nevent: token\ndata: 一把新的\n\n"
            + "id: 8\nevent: token\ndata: 传奇长剑\n\n"
            + "id: 9\nevent: token\ndata: 吗？\n\n"
            + "id: 10\nevent: done\ndata: [DONE]\n\n"
    ).getBytes(StandardCharsets.UTF_8);

    @Benchmark public void parseTenEvents(Blackhole bh) {
        SseParser parser = new SseParser(bh::consume);
        parser.feed(ByteBuffer.wrap(TEN_EVENTS));
        parser.finish();
    }
}
