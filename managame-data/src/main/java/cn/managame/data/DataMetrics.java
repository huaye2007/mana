package cn.managame.data;

import cn.managame.data.internal.ChangeSet;
import cn.managame.data.internal.LogQueue;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;

/**
 * What the repositories report to the application's {@link Metrics}: the numbers {@link GameData#stats()} reads, by
 * entity type (the tag {@code entity}, the class's name) and by log type ({@code log}). Each is counted already, by the
 * cache, the change set or the log queue, and read when the backend collects, so nothing is counted again on a read, a
 * change or a write. The meters are made once, at build, and closed with GameData.
 */
final class DataMetrics {
    private DataMetrics() {}

    /** The meters of one entity repository; {@code cache} holds its entities, or its groups. */
    static List<Meter> entity(Metrics metrics, Class<?> entityClass, Cache<?, ?> cache, ChangeSet changes) {
        String type = entityClass.getName();
        return List.of(
                metrics.gauge("managame.data.cache.entries", cache::estimatedSize, "entity", type),
                metrics.counter("managame.data.cache.hits", () -> cache.stats().hitCount(), "entity", type),
                metrics.counter("managame.data.cache.misses", () -> cache.stats().missCount(), "entity", type),
                metrics.counter("managame.data.cache.loads", () -> cache.stats().loadSuccessCount(), "entity", type),
                metrics.counter("managame.data.cache.load_failures", () -> cache.stats().loadFailureCount(), "entity", type),
                metrics.counter("managame.data.cache.load_nanos", () -> cache.stats().totalLoadTime(), "entity", type),
                metrics.counter("managame.data.cache.evictions", () -> cache.stats().evictionCount(), "entity", type),
                metrics.gauge("managame.data.changes.waiting", changes::waiting, "entity", type),
                metrics.counter("managame.data.changes.written", changes::written, "entity", type),
                metrics.counter("managame.data.changes.write_failures", changes::failures, "entity", type),
                metrics.counter("managame.data.changes.dropped", changes::dropped, "entity", type));
    }

    /** The meters of one log repository. */
    static List<Meter> log(Metrics metrics, LogQueue queue) {
        String type = queue.type().getName();
        return List.of(
                metrics.gauge("managame.data.logs.queued", queue::queued, "log", type),
                metrics.counter("managame.data.logs.written", queue::written, "log", type),
                metrics.counter("managame.data.logs.refused", queue::refused, "log", type),
                metrics.counter("managame.data.logs.write_failures", queue::failures, "log", type),
                metrics.counter("managame.data.logs.dropped", queue::dropped, "log", type));
    }
}
