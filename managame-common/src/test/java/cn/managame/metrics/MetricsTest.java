package cn.managame.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class MetricsTest {
    /** An application's own backend, here readings kept in memory, behind the framework's Metrics. */
    static final class MemoryMetrics implements Metrics {
        final Map<String, DoubleSupplier> readings = new ConcurrentHashMap<>();

        double read(String key) { return readings.get(key).getAsDouble(); }

        @Override public Counter counter(String name, String... tags) {
            LongAdder count = new LongAdder();
            readings.put(key(name, tags), count::sum);
            return count::add;
        }

        @Override public Meter counter(String name, LongSupplier count, String... tags) {
            String key = key(name, tags);
            readings.put(key, count::getAsLong);
            return () -> readings.remove(key);
        }

        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            String key = key(name, tags);
            readings.put(key, value);
            return () -> readings.remove(key);
        }

        private static String key(String name, String[] tags) { return tags.length == 0 ? name : name + List.of(tags); }
    }

    @Test void anApplicationReportsThroughItsOwnBackend() {
        MemoryMetrics metrics = new MemoryMetrics();
        AtomicLong written = new AtomicLong();
        List<String> online = new CopyOnWriteArrayList<>(List.of("ann", "bob"));
        Counter refused = metrics.counter("managame.runtime.tasks.refused", "domain", "1");
        Meter writtenMeter = metrics.counter("managame.data.changes.written", written::get);
        metrics.gauge("game.players.online", online::size);

        refused.increment();
        refused.increment(2);
        written.addAndGet(5);
        online.add("cid");

        assertEquals(3, metrics.read("managame.runtime.tasks.refused[domain, 1]"));
        assertEquals(5, metrics.read("managame.data.changes.written"));
        assertEquals(3, metrics.read("game.players.online"));

        writtenMeter.close();
        assertFalse(metrics.readings.containsKey("managame.data.changes.written"));
    }

    @Test void noopIsOneEmptyMeter() {
        Metrics metrics = Metrics.noop();
        Counter counter = metrics.counter("managame.rpc.calls", "node", "7");

        counter.increment();
        metrics.gauge("managame.rpc.calls.waiting", () -> 1).close();

        assertSame(counter, metrics.counter("managame.rpc.calls.answered", () -> 1));
    }
}
