package cn.managame.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * How long something took, added up in fixed buckets and read as counters when the backend collects, so that two
 * readings give the mean and the distribution in between. For a component that times what costs far more than reading
 * the clock. Safe from any thread, and striped, so that threads recording at once do not contend.
 */
public final class LatencyBuckets {
    /** The upper bound of every bucket but the last, in nanoseconds. */
    private static final long[] BOUNDS = {500_000, 1_000_000, 5_000_000, 10_000_000, 50_000_000, 100_000_000, 500_000_000};
    /** The bounds in milliseconds, as the {@code le} tag says them. */
    private static final String[] LABELS = {"0.5", "1", "5", "10", "50", "100", "500", "+Inf"};
    private final LongAdder[] buckets = new LongAdder[LABELS.length];
    private final LongAdder totalNanos = new LongAdder();

    public LatencyBuckets() {
        Arrays.setAll(buckets, i -> new LongAdder());
    }

    public void record(long nanos) {
        int bucket = 0;
        while (bucket < BOUNDS.length && nanos > BOUNDS[bucket]) bucket++;
        buckets[bucket].increment();
        totalNanos.add(nanos);
    }

    /** What was recorded within the {@code bucket}-th bound; the last counts everything. */
    long within(int bucket) {
        long count = 0;
        for (int i = 0; i <= bucket; i++) count += buckets[i].sum();
        return count;
    }

    long totalNanos() { return totalNanos.sum(); }

    /**
     * Reports {@code name_bucket}, one counter per bound tagged {@code le} in milliseconds ({@code 0.5}, {@code 1},
     * {@code 5}, {@code 10}, {@code 50}, {@code 100}, {@code 500} and {@code +Inf}, which counts everything), and
     * {@code name_nanos}, the total, each with {@code tags} too. The meters hold this until closed.
     */
    public List<Meter> report(Metrics metrics, String name, String... tags) {
        List<Meter> meters = new ArrayList<>(LABELS.length + 1);
        for (int i = 0; i < LABELS.length; i++) {
            int bucket = i;
            String[] bucketTags = Arrays.copyOf(tags, tags.length + 2);
            bucketTags[tags.length] = "le";
            bucketTags[tags.length + 1] = LABELS[i];
            meters.add(metrics.counter(name + "_bucket", () -> within(bucket), bucketTags));
        }
        meters.add(metrics.counter(name + "_nanos", this::totalNanos, tags));
        return meters;
    }
}
