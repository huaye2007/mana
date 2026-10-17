package cn.managame.core.metrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Where components publish their readings and the application collects them. The registry holds no values of
 * its own: it keeps the published {@link MetricsSource}s and, on {@link #read}, asks each one for its current
 * readings and passes them on with its prefix applied. Collection therefore costs nothing until something
 * collects, and instrumenting a component adds no work to its data path.
 *
 * <p>This is the whole contract between the framework and whatever collects: Core defines the shape, each
 * component publishes a snapshot type, and the integration layer adapts those to a registry and exports
 * them. Core itself depends on nothing and knows no exposition format.
 *
 * <p>Publishing and withdrawal are safe from any thread and are expected to happen at assembly and shutdown,
 * not continuously: each one copies the source table. {@link #read} is lock-free and may run concurrently
 * with either, and with another read.
 */
public final class MetricsRegistry {
    /** Longest accepted prefix or metric name, so an exported name stays readable and bounded. */
    public static final int MAX_NAME_LENGTH = 128;

    private record Entry(String prefix, MetricsSource source) {}

    private volatile Entry[] entries = {};

    /**
     * Publishes {@code source} under {@code prefix}; publishing the same prefix again replaces its source.
     * The prefix is a lowercase dotted path such as {@code rpc} or {@code network.client}: each segment
     * starts with a letter and continues with letters or digits, and the whole prefix is at most
     * {@link #MAX_NAME_LENGTH} characters.
     */
    public synchronized void publish(String prefix, MetricsSource source) {
        name(prefix, "prefix");
        Objects.requireNonNull(source, "source");
        Entry entry = new Entry(prefix, source);
        Entry[] current = entries;
        for (int i = 0; i < current.length; i++) {
            if (current[i].prefix().equals(prefix)) {
                Entry[] replaced = current.clone();
                replaced[i] = entry;
                entries = replaced;
                return;
            }
        }
        Entry[] next = Arrays.copyOf(current, current.length + 1);
        next[next.length - 1] = entry;
        Arrays.sort(next, Comparator.comparing(Entry::prefix));
        entries = next;
    }

    /** Stops reporting the source published under {@code prefix}; false when nothing was published there. */
    public synchronized boolean withdraw(String prefix) {
        name(prefix, "prefix");
        Entry[] current = entries;
        for (int i = 0; i < current.length; i++) {
            if (current[i].prefix().equals(prefix)) {
                Entry[] next = new Entry[current.length - 1];
                System.arraycopy(current, 0, next, 0, i);
                System.arraycopy(current, i + 1, next, i, current.length - 1 - i);
                entries = next;
                return true;
            }
        }
        return false;
    }

    /** The published prefixes, in the order {@link #read} visits them. */
    public List<String> prefixes() {
        Entry[] current = entries;
        List<String> result = new ArrayList<>(current.length);
        for (Entry entry : current) result.add(entry.prefix());
        return List.copyOf(result);
    }

    /**
     * Reads every published source in prefix order and reports its readings to {@code sink}, each name
     * prefixed with {@code <prefix>.}. A name a source reports must satisfy the same rule as a prefix, and
     * the prefixed name must still fit {@link #MAX_NAME_LENGTH}; a name that does not throws
     * IllegalArgumentException, which tells the component's author rather than exporting something a
     * collector cannot parse. A source that throws ends the collection: later sources are not read.
     */
    public void read(MetricSink sink) {
        Objects.requireNonNull(sink, "sink");
        for (Entry entry : entries) entry.source().read(new Prefixed(entry.prefix(), sink));
    }

    /** Applies one source's prefix and validates what it reports. */
    private record Prefixed(String prefix, MetricSink sink) implements MetricSink {
        @Override public void gauge(String metric, long value) { sink.gauge(qualify(metric), value); }
        @Override public void counter(String metric, long value) { sink.counter(qualify(metric), value); }
        private String qualify(String metric) {
            name(metric, "metric name");
            String qualified = prefix + '.' + metric;
            if (qualified.length() > MAX_NAME_LENGTH)
                throw new IllegalArgumentException("Prefixed metric name exceeds " + MAX_NAME_LENGTH + ": " + qualified);
            return qualified;
        }
    }

    /**
     * Accepts a lowercase dotted path: segments of a letter followed by letters or digits, separated by
     * single dots, no leading, trailing or repeated dot. Checked with a character scan rather than a regular
     * expression, as elsewhere in the repository.
     */
    private static void name(String value, String what) {
        Objects.requireNonNull(value, what);
        int length = value.length();
        if (length == 0 || length > MAX_NAME_LENGTH)
            throw new IllegalArgumentException(what + " must be 1.." + MAX_NAME_LENGTH + " characters");
        boolean segmentStart = true;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (segmentStart) throw new IllegalArgumentException(what + " has an empty segment: " + value);
                segmentStart = true;
                continue;
            }
            boolean letter = c >= 'a' && c <= 'z';
            if (segmentStart ? !letter : !(letter || (c >= '0' && c <= '9')))
                throw new IllegalArgumentException(what + " must be lowercase dotted, letter-led segments: " + value);
            segmentStart = false;
        }
        if (segmentStart) throw new IllegalArgumentException(what + " must not end with a dot: " + value);
    }
}
