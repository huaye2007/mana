package cn.managame.benchmark;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

/**
 * What a benchmark costs besides time, measured around every iteration and reported next to JMH's own results:
 * <ul>
 * <li>{@code cost.cpu}: CPU time of the benchmark JVM per operation, split into {@code cost.cpu.caller}, the
 * benchmark's own threads, {@code cost.cpu.eventloop}, Netty's threads, {@code cost.cpu.other}, every other Java
 * thread such as timers and executors, and {@code cost.cpu.jvm}, the rest: garbage collection, compilation and
 * threads that ended during the iteration;</li>
 * <li>{@code cost.net.sent} and {@code cost.net.received}: bytes on the wire per operation, as
 * {@link CountingSocketChannel} counts them on the dialing side; absent when nothing was counted;</li>
 * <li>{@code cost.direct}: direct memory Netty holds at the end of the iteration, in MiB, the highest of the
 * iterations;</li>
 * <li>{@code cost.peer.*}: the same, with heap allocation and garbage collection, for a {@link PeerProcess} the
 * benchmark runs its other side in, from the peer's own measurement;</li>
 * <li>{@code cost.busy}: how much of the machine's CPU everything but the benchmark and its peer used, in percent,
 * the highest of the iterations, so that a run the rest of the machine disturbed shows.</li>
 * </ul>
 * Operations are JMH's count for the whole iteration, as its GC profiler normalizes them. The iteration that starts
 * a peer reports nothing of it; the one that stops it, what it used until then.
 */
public final class CostProfiler implements InternalProfiler {
    private final Usage usage = new Usage();
    private long started, sent, received;
    private PeerProcess peer;

    @Override public String getDescription() {
        return "CPU per operation by kind of thread, bytes on the wire, Netty's direct memory, a peer process's costs "
                + "and the machine's load";
    }

    @Override public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        peer = PeerProcess.current();
        if (peer != null) peer.begin();
        usage.machineLoad(); // the next reading covers the iteration
        sent = CountingSocketChannel.SENT.sum();
        received = CountingSocketChannel.RECEIVED.sum();
        usage.begin();
        started = System.nanoTime();
    }

    @Override public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams,
                                                                 IterationParams iterationParams, IterationResult result) {
        long elapsed = System.nanoTime() - started;
        Usage.Interval own = usage.end();
        double machineLoad = usage.machineLoad();
        Usage.Interval other = peer == null ? null : peer.end();
        double ops = Math.max(1, result.getMetadata().getAllOps());
        List<Result> results = new ArrayList<>(List.of(
                perOp("cost.cpu", own.process(), ops), perOp("cost.cpu.caller", own.caller(), ops),
                perOp("cost.cpu.eventloop", own.eventLoop(), ops), perOp("cost.cpu.other", own.other(), ops),
                perOp("cost.cpu.jvm", own.jvm(), ops), mib("cost.direct", own.direct())));
        long sentNow = CountingSocketChannel.SENT.sum() - sent, receivedNow = CountingSocketChannel.RECEIVED.sum() - received;
        if (sentNow + receivedNow > 0) {
            results.add(new ScalarResult("cost.net.sent", sentNow / ops, "B/op", AggregationPolicy.AVG));
            results.add(new ScalarResult("cost.net.received", receivedNow / ops, "B/op", AggregationPolicy.AVG));
        }
        long ours = own.process();
        if (other != null) {
            ours += other.process();
            results.addAll(List.of(perOp("cost.peer.cpu", other.process(), ops),
                    perOp("cost.peer.cpu.eventloop", other.eventLoop(), ops), perOp("cost.peer.cpu.other", other.other(), ops),
                    perOp("cost.peer.cpu.jvm", other.jvm(), ops), mib("cost.peer.direct", other.direct()),
                    new ScalarResult("cost.peer.alloc", other.allocated() / ops, "B/op", AggregationPolicy.AVG),
                    new ScalarResult("cost.peer.gc.count", other.gcCount(), "counts", AggregationPolicy.SUM),
                    new ScalarResult("cost.peer.gc.time", other.gcMillis(), "ms", AggregationPolicy.SUM)));
        }
        if (machineLoad >= 0) {
            double share = ours / ((double) elapsed * Runtime.getRuntime().availableProcessors());
            results.add(new ScalarResult("cost.busy", 100 * Math.max(0, machineLoad - share), "%", AggregationPolicy.MAX));
        }
        return results;
    }

    private static ScalarResult perOp(String label, long nanos, double ops) {
        return new ScalarResult(label, nanos / ops, "ns/op", AggregationPolicy.AVG);
    }

    private static ScalarResult mib(String label, long bytes) {
        return new ScalarResult(label, bytes / (1024.0 * 1024), "MiB", AggregationPolicy.MAX);
    }
}
