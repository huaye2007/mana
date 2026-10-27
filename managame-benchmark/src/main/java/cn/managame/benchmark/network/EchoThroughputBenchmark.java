package cn.managame.benchmark.network;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Messages echoed per second with many in flight: four sending threads share a window of messages not yet echoed,
 * and each echo frees room. The same load as rpc's CallThroughputBenchmark, so what RPC adds per call is the
 * difference between the two. A message a connection refuses fails the benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class EchoThroughputBenchmark {
    @Param({"64", "1024"})
    public int payload;
    @Param({"1", "4"})
    public int connections;
    @Param({"1024"})
    public int window;
    private EchoFixture fixture;
    private Semaphore inFlight;
    private final AtomicLong refused = new AtomicLong();

    @Setup(Level.Trial) public void start() throws InterruptedException {
        inFlight = new Semaphore(window);
        fixture = new EchoFixture(payload, connections, inFlight::release);
    }

    /** Lets the messages of an iteration come back before the next starts or the connections close. */
    @TearDown(Level.Iteration) public void settle() throws InterruptedException {
        inFlight.acquire(window);
        inFlight.release(window);
        if (refused.get() > 0) throw new IllegalStateException(refused.get() + " messages were refused");
    }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    @Benchmark public void echo(ThreadParams thread) throws InterruptedException {
        inFlight.acquire();
        if (!fixture.send(thread.getThreadIndex())) {
            refused.incrementAndGet();
            inFlight.release();
        }
    }
}
