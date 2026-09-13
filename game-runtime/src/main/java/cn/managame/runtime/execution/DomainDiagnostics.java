package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.diagnostics.SlowTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Domain-local bounded samples. Readers never acquire a lock needed by business workers. */
final class DomainDiagnostics {
    private static final long ORIGIN = System.nanoTime();
    record Sample(long sequence, long recordedAt, SlowTask task) {}
    static final Comparator<Sample> ORDER = Comparator.comparingLong(Sample::recordedAt);
    private final long thresholdNanos;
    private final AtomicReferenceArray<Sample> samples;
    private final AtomicLong sequence = new AtomicLong();

    DomainDiagnostics(Duration threshold, int capacity) {
        thresholdNanos = threshold.toNanos();
        samples = new AtomicReferenceArray<>(capacity);
    }

    void record(String domain, HandlerContext context, String source, String thread,
                long wait, long elapsed, boolean failed) {
        int capacity = samples.length();
        if (capacity == 0 || elapsed < thresholdNanos) return;
        long ticket = sequence.getAndIncrement();
        var task = new SlowTask(domain, context.route(), source, thread,
                Duration.ofNanos(wait), Duration.ofNanos(elapsed), failed);
        var sample = new Sample(ticket, System.nanoTime() - ORIGIN, task);
        int index = Math.floorMod(ticket, capacity);
        Sample previous;
        do {
            previous = samples.get(index);
            // A delayed publisher must not overwrite a newer sample after a ring wrap.
            if (previous != null && previous.sequence() > ticket) return;
        } while (!samples.compareAndSet(index, previous, sample));
    }

    List<Sample> snapshot() {
        long end = sequence.get();
        long start = Math.max(0, end - samples.length());
        List<Sample> result = new ArrayList<>((int) (end - start));
        for (long ticket = start; ticket < end; ticket++) {
            Sample sample = samples.get(Math.floorMod(ticket, samples.length()));
            if (sample != null && sample.sequence() == ticket) result.add(sample);
        }
        return result;
    }
}
