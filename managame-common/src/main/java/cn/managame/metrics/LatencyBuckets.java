package cn.managame.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * How long something took, added up in fixed buckets and read as counters when the backend collects, so that two
 * readings give the mean and the distribution in between. For a component that times what costs far more than reading
 * the clock. Safe from any thread, and striped, so that threads recording at once do not contend.
 */
public final class LatencyBuckets {
    /** Default upper bounds for sub-second runtime/RPC operations, in nanoseconds. */
    private static final long[] DEFAULT_BOUNDS = {500_000, 1_000_000, 5_000_000, 10_000_000, 50_000_000, 100_000_000, 500_000_000};
    /** Default bounds in milliseconds, as the {@code le} tag says them. */
    private static final String[] DEFAULT_LABELS = {"0.5", "1", "5", "10", "50", "100", "500", "+Inf"};

    private final long[] bounds;
    private final String[] labels;
    private final LongAdder[] buckets;
    private final LongAdder totalNanos = new LongAdder();

    /** Default buckets ({@code 0.5} ms to {@code 500} ms plus {@code +Inf}), suitable for runtime and RPC operations. */
    public LatencyBuckets() {
        this(DEFAULT_BOUNDS, DEFAULT_LABELS);
    }

    private LatencyBuckets(long[] bounds, String[] labels) {
        this.bounds = bounds;
        this.labels = labels;
        this.buckets = new LongAdder[labels.length];
        Arrays.setAll(buckets, i -> new LongAdder());
    }

    /**
     * Buckets with custom strictly increasing millisecond upper bounds plus {@code +Inf}, for slower external
     * operations such as AI / LLM calls (for example {@code ofMillis(50, 100, 250, 500, 1000, 2000, 5000, 10000, 30000)}).
     */
    public static LatencyBuckets ofMillis(long... boundsMillis) {
        if (boundsMillis == null || boundsMillis.length == 0)
            throw new IllegalArgumentException("At least one positive bound is required");
        long[] bounds = new long[boundsMillis.length];
        String[] labels = new String[boundsMillis.length + 1];
        long prev = 0;
        for (int i = 0; i < boundsMillis.length; i++) {
            long ms = boundsMillis[i];
            if (ms <= prev) throw new IllegalArgumentException("Bounds must be positive and strictly increasing");
            prev = ms;
            bounds[i] = TimeUnit.MILLISECONDS.toNanos(ms);
            labels[i] = Long.toString(ms);
        }
        labels[boundsMillis.length] = "+Inf";
        return new LatencyBuckets(bounds, labels);
    }

    public void record(long nanos) {
        int bucket = 0;
        while (bucket < bounds.length && nanos > bounds[bucket]) bucket++;
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
     * Reports {@code name_bucket}, one counter per bound tagged {@code le} in milliseconds (plus {@code +Inf},
     * which counts everything), and {@code name_nanos}, the total, each with {@code tags} too.
     * The meters hold this until closed.
     */
    public List<Meter> report(Metrics metrics, String name, String... tags) {
        List<Meter> meters = new ArrayList<>(labels.length + 1);
        for (int i = 0; i < labels.length; i++) {
            int bucket = i;
            String[] bucketTags = Arrays.copyOf(tags, tags.length + 2);
            bucketTags[tags.length] = "le";
            bucketTags[tags.length + 1] = labels[i];
            meters.add(metrics.counter(name + "_bucket", () -> within(bucket), bucketTags));
        }
        meters.add(metrics.counter(name + "_nanos", this::totalNanos, tags));
        return meters;
    }
}
