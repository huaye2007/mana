package cn.managame.benchmark.rpc;

import cn.managame.network.connection.WriteStatus;
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
 * Notifies handled per second: four sending threads, each with its own route key, share a window of notifies not
 * yet handled by the receiver, which frees room as it handles them. Both nodes run in this process, so the
 * receiver can tell the senders directly. A notify the sending node refuses fails the benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class NotifyThroughputBenchmark {
    @Param({"64", "1024"})
    public int payload;
    @Param({"1", "4"})
    public int connections;
    @Param({"1024"})
    public int window;
    private RpcFixture fixture;
    private final AtomicLong refused = new AtomicLong();

    @Setup(Level.Trial) public void start() throws Exception {
        fixture = new RpcFixture(payload, connections, otherProcess());
        fixture.notified.release(window);
    }

    @TearDown(Level.Iteration) public void settle() throws InterruptedException {
        fixture.settleNotifies(window);
        if (refused.get() > 0) throw new IllegalStateException(refused.get() + " notifies were refused");
    }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    /** Whether the world server runs in a process of its own; here it does not. */
    protected boolean otherProcess() { return false; }

    @Benchmark public void notifyPeer(ThreadParams thread) throws InterruptedException {
        fixture.notified.acquire();
        if (fixture.peer.notify(fixture.notify(thread.getThreadIndex())) != WriteStatus.ACCEPTED) {
            refused.incrementAndGet();
            fixture.notified.release();
        }
    }
}
