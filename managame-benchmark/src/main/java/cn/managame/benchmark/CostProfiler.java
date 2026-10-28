package cn.managame.benchmark;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

/**
 * What a benchmark costs besides time, measured in each benchmark JVM around every iteration and reported next to
 * JMH's own results:
 * <ul>
 * <li>{@code cost.cpu}: CPU time of the whole JVM per operation, split into {@code cost.cpu.caller}, the benchmark's
 * own threads, {@code cost.cpu.eventloop}, Netty's threads, {@code cost.cpu.other}, every other Java thread such as
 * timers and executors, and {@code cost.cpu.jvm}, the rest: garbage collection, compilation and threads that ended
 * during the iteration;</li>
 * <li>{@code cost.net.sent} and {@code cost.net.received}: bytes on the wire per operation, as
 * {@link CountingSocketChannel} counts them on the dialing side; absent when nothing was counted;</li>
 * <li>{@code cost.direct}: direct memory Netty holds at the end of the iteration, in MiB, the highest of the
 * iterations;</li>
 * <li>{@code cost.busy}: how much of the machine's CPU everything but this JVM used, in percent, the highest of the
 * iterations, so that a run the rest of the machine disturbed shows.</li>
 * </ul>
 * Operations are JMH's count for the whole iteration, as its GC profiler normalizes them.
 */
public final class CostProfiler implements InternalProfiler {
    private static final String CALLER = "caller", EVENT_LOOP = "eventloop", OTHER = "other";
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final Map<Long, Long> threadCpu = new HashMap<>();
    private long started, processCpu, sent, received;

    @Override public String getDescription() {
        return "CPU per operation by kind of thread, bytes on the wire, Netty's direct memory and the machine's load";
    }

    @Override public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        threadCpu.clear();
        for (Thread thread : liveThreads()) threadCpu.put(thread.threadId(), threads.getThreadCpuTime(thread.threadId()));
        os.getCpuLoad(); // the next reading covers the iteration
        sent = CountingSocketChannel.SENT.sum();
        received = CountingSocketChannel.RECEIVED.sum();
        processCpu = os.getProcessCpuTime();
        started = System.nanoTime();
    }

    @Override public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams,
                                                                 IterationParams iterationParams, IterationResult result) {
        long elapsed = System.nanoTime() - started, process = os.getProcessCpuTime() - processCpu;
        double machineLoad = os.getCpuLoad();
        Map<String, Long> byKind = new HashMap<>(Map.of(CALLER, 0L, EVENT_LOOP, 0L, OTHER, 0L));
        for (Thread thread : liveThreads()) {
            long now = threads.getThreadCpuTime(thread.threadId());
            if (now >= 0) byKind.merge(kind(thread), now - threadCpu.getOrDefault(thread.threadId(), 0L), Long::sum);
        }
        double ops = Math.max(1, result.getMetadata().getAllOps());
        List<Result> results = new ArrayList<>();
        results.add(perOp("cost.cpu", process, ops));
        long java = 0;
        for (String kind : List.of(CALLER, EVENT_LOOP, OTHER)) {
            results.add(perOp("cost.cpu." + kind, byKind.get(kind), ops));
            java += byKind.get(kind);
        }
        results.add(perOp("cost.cpu.jvm", Math.max(0, process - java), ops));
        long sentNow = CountingSocketChannel.SENT.sum() - sent, receivedNow = CountingSocketChannel.RECEIVED.sum() - received;
        if (sentNow + receivedNow > 0) {
            results.add(new ScalarResult("cost.net.sent", sentNow / ops, "B/op", AggregationPolicy.AVG));
            results.add(new ScalarResult("cost.net.received", receivedNow / ops, "B/op", AggregationPolicy.AVG));
        }
        results.add(new ScalarResult("cost.direct", directMemory() / (1024.0 * 1024), "MiB", AggregationPolicy.MAX));
        if (machineLoad >= 0) {
            double own = process / ((double) elapsed * Runtime.getRuntime().availableProcessors());
            results.add(new ScalarResult("cost.busy", 100 * Math.max(0, machineLoad - own), "%", AggregationPolicy.MAX));
        }
        return results;
    }

    private static ScalarResult perOp(String label, long nanos, double ops) {
        return new ScalarResult(label, nanos / ops, "ns/op", AggregationPolicy.AVG);
    }

    /** The benchmark's own threads are JMH's workers; Netty's EventLoops run on its own kind of thread. */
    private static String kind(Thread thread) {
        if (thread.getName().contains("jmh-worker")) return CALLER;
        return thread instanceof FastThreadLocalThread ? EVENT_LOOP : OTHER;
    }

    /** Every live platform thread; virtual threads run on carriers, which are counted instead. */
    private static List<Thread> liveThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] all = new Thread[root.activeCount() * 2 + 16];
        return List.of(Arrays.copyOf(all, root.enumerate(all, true)));
    }

    /** Netty counts the direct memory it allocates without a cleaner; otherwise the JDK's direct pool holds it. */
    private static long directMemory() {
        long netty = PlatformDependent.usedDirectMemory();
        if (netty >= 0) return netty;
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
    }
}
