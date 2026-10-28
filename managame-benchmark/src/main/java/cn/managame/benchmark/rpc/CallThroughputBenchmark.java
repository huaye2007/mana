package cn.managame.benchmark.rpc;

import cn.managame.FrameworkErrorCodes;
import cn.managame.rpc.RpcCallback;
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
 * Calls answered per second with many in flight, as busy game servers make them: four calling threads, each with
 * its own route key, share a window of calls waiting for their answer. A call waits for room in the window before
 * it is sent, so the rate is the rate answers come back. Calls that fail are counted and fail the benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class CallThroughputBenchmark {
    @Param({"64", "1024"})
    public int payload;
    @Param({"1", "4"})
    public int connections;
    @Param({"1024"})
    public int window;
    private RpcFixture fixture;
    private Semaphore inFlight;
    private final AtomicLong failures = new AtomicLong();
    private RpcCallback done;

    @Setup(Level.Trial) public void start() throws Exception {
        fixture = new RpcFixture(payload, connections, otherProcess());
        inFlight = new Semaphore(window);
        done = (request, response) -> {
            if (response.code() != FrameworkErrorCodes.SUCCESS) failures.incrementAndGet();
            inFlight.release();
        };
    }

    /** Lets the calls of an iteration finish before the next starts or the nodes close. */
    @TearDown(Level.Iteration) public void settle() throws InterruptedException {
        inFlight.acquire(window);
        inFlight.release(window);
        if (failures.get() > 0) throw new IllegalStateException(failures.get() + " calls failed");
    }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    /** Whether the world server runs in a process of its own; here it does not. */
    protected boolean otherProcess() { return false; }

    @Benchmark public void call(ThreadParams thread) throws InterruptedException {
        inFlight.acquire();
        fixture.peer.call(fixture.request(thread.getThreadIndex()), done);
    }
}
