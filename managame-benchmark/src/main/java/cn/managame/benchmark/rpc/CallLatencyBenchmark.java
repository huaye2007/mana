package cn.managame.benchmark.rpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The round trip of one call at a time: the caller sends, the callee answers on its EventLoop, and the caller's
 * thread wakes up with the answer. Sampled, so the result shows percentiles.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class CallLatencyBenchmark {
    @Param({"64", "1024", "16384"})
    public int payload;
    private RpcFixture fixture;

    @Setup(Level.Trial) public void start() throws InterruptedException { fixture = new RpcFixture(payload, 1); }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    @Benchmark public int call() throws Exception {
        CompletableFuture<Integer> answer = new CompletableFuture<>();
        fixture.peer.call(fixture.request(0), (request, response) -> answer.complete(response.code()));
        return answer.get();
    }
}
