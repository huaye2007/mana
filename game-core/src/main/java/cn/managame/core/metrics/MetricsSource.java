package cn.managame.core.metrics;

/**
 * Something that can report its current readings on demand. A source reads values it already keeps — a
 * counter field, a map size — and reports them; it does not compute, aggregate over time or allocate per
 * reading. Nothing in the framework calls a source on a hot path: the collector decides when to read.
 *
 * <p>A source MUST NOT throw. {@link MetricsRegistry#read} does not isolate a failing source, so an
 * exception there ends that collection and the readings of later sources are lost.
 */
@FunctionalInterface
public interface MetricsSource {
    /** Reports every current reading into {@code sink}. Called on the collecting thread. */
    void read(MetricSink sink);
}
