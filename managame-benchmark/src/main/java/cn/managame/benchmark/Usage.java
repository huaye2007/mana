package cn.managame.benchmark;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What this JVM used between {@link #begin()} and {@link #end()}: CPU time in all and by kind of thread, heap
 * allocated, garbage collections, and the direct memory Netty holds at the end. A benchmark JVM measures itself with
 * it through {@link CostProfiler}, and so does a {@link PeerProcess}.
 *
 * <p>A thread's CPU time can only be read while it lives, and threads end within an interval: JMH tears a fixture
 * down, stopping its EventLoops, before the last iteration's profilers run. So a sampler reads every thread every
 * {@value #SAMPLE_MILLIS} milliseconds, and a thread that ended counts with its last reading.
 */
final class Usage {
    private static final long SAMPLE_MILLIS = 50;
    private static final int CALLER = 0, EVENT_LOOP = 1, OTHER = 2;
    private final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    /** Every thread seen since the interval began, by id. */
    private final Map<Long, Seen> seen = new ConcurrentHashMap<>();
    private ScheduledExecutorService sampler;
    private long processCpu, allocated, gcCount, gcMillis;

    /** A thread's kind, its CPU time when the interval began, or 0 when it began later, and its latest. */
    private record Seen(int kind, long start, long latest) {}

    /**
     * One interval: CPU nanoseconds of the whole JVM and of the benchmark's own threads (JMH's workers), Netty's
     * threads and every other Java thread, bytes allocated on the heap, collections and their milliseconds, and
     * bytes of direct memory at its end.
     */
    record Interval(long process, long caller, long eventLoop, long other, long allocated, long gcCount, long gcMillis,
                    long direct) {
        /** What no Java thread accounts for: garbage collection and compilation. */
        long jvm() { return Math.max(0, process - caller - eventLoop - other); }

        String line() {
            return process + " " + caller + " " + eventLoop + " " + other + " " + allocated + " " + gcCount + " " + gcMillis + " " + direct;
        }

        static Interval parse(String line) {
            long[] values = Arrays.stream(line.trim().split(" ")).mapToLong(Long::parseLong).toArray();
            return new Interval(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]);
        }
    }

    void begin() {
        seen.clear();
        for (Thread thread : liveThreads()) {
            long now = threads.getThreadCpuTime(thread.threadId());
            if (now >= 0) seen.put(thread.threadId(), new Seen(kind(thread), now, now));
        }
        if (sampler == null) {
            sampler = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().name("bench-usage").daemon().unstarted(task));
            sampler.scheduleAtFixedRate(this::sample, SAMPLE_MILLIS, SAMPLE_MILLIS, TimeUnit.MILLISECONDS);
        }
        allocated = threads.getTotalThreadAllocatedBytes();
        gcCount = gcCount();
        gcMillis = gcMillis();
        processCpu = os.getProcessCpuTime();
    }

    Interval end() {
        long process = os.getProcessCpuTime() - processCpu;
        sample();
        long[] byKind = new long[3];
        for (Seen thread : seen.values()) byKind[thread.kind()] += thread.latest() - thread.start();
        return new Interval(process, byKind[CALLER], byKind[EVENT_LOOP], byKind[OTHER],
                threads.getTotalThreadAllocatedBytes() - allocated, gcCount() - gcCount, gcMillis() - gcMillis, directMemory());
    }

    /** Reads every live thread; one that started since the interval began counts from nothing. */
    private void sample() {
        for (Thread thread : liveThreads()) {
            long now = threads.getThreadCpuTime(thread.threadId());
            if (now >= 0) seen.merge(thread.threadId(), new Seen(kind(thread), 0, now),
                    (old, update) -> new Seen(old.kind(), old.start(), Math.max(old.latest(), now)));
        }
    }

    /** The share of the machine's CPU everything used since the previous call, from 0 to 1; negative when unknown. */
    double machineLoad() { return os.getCpuLoad(); }

    private static int kind(Thread thread) {
        if (thread.getName().contains("jmh-worker")) return CALLER;
        return thread instanceof FastThreadLocalThread ? EVENT_LOOP : OTHER;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
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
