package cn.managame.core.metrics;

/**
 * Receives the readings of one {@link MetricsSource}. The application implements it to forward them wherever
 * it collects: an HTTP exposition format, a metrics library, a log line. Implementations are called on the
 * collecting thread, once per reading, and must not block or call back into the registry.
 *
 * <p>Names are lowercase dotted paths, for example {@code rpc.calls.pending}. The registry prefixes every
 * name with the prefix its source was published under, so a sink always sees the fully qualified name.
 */
public interface MetricSink {
    /** A value that rises and falls: a queue depth, an open-connection count, a cache size. */
    void gauge(String name, long value);

    /**
     * A value that only rises while the process runs: messages, errors, drops. A reader derives a rate by
     * differencing two readings; the framework never resets one.
     */
    void counter(String name, long value);
}
