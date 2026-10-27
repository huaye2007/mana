package cn.managame.benchmark.network;

import java.util.concurrent.Semaphore;
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
 * The round trip of one message at a time: the sending thread writes, the server echoes on its EventLoop, and the
 * sending thread wakes up with the echo. The same path as rpc's CallLatencyBenchmark without RPC. Sampled, so the
 * result shows percentiles.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class EchoLatencyBenchmark {
    @Param({"64", "1024", "16384"})
    public int payload;
    private final Semaphore echoed = new Semaphore(0);
    private EchoFixture fixture;

    @Setup(Level.Trial) public void start() throws InterruptedException { fixture = new EchoFixture(payload, 1, echoed::release); }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    @Benchmark public void echo() throws InterruptedException {
        if (!fixture.send(0)) throw new IllegalStateException("The connection refused the message");
        echoed.acquire();
    }
}
