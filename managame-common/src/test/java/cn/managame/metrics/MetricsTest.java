package cn.managame.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        @Override public Timer timer(String name, String... tags) {
            String key = key(name, tags);
            LongAdder count = new LongAdder(), nanos = new LongAdder();
            readings.put(key + ".count", count::sum);
            readings.put(key + ".nanos", nanos::sum);
            return recorded -> {
                count.increment();
                nanos.add(recorded);
            };
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

    @Test void timerStartAndStopRecordTheTimeBetween() throws InterruptedException {
        MemoryMetrics metrics = new MemoryMetrics();
        Timer tasks = metrics.timer("managame.runtime.tasks", "domain", "1");

        long start = tasks.start();
        Thread.sleep(2);
        tasks.stop(start);
        tasks.record(1_000);

        assertEquals(2, metrics.read("managame.runtime.tasks[domain, 1].count"));
        assertTrue(metrics.read("managame.runtime.tasks[domain, 1].nanos") >= 2_001_000);
    }

    @Test void noopIsOneEmptyMeterThatReadsNoClock() {
        Metrics metrics = Metrics.noop();
        Counter counter = metrics.counter("managame.rpc.calls", "node", "7");
        Timer timer = metrics.timer("managame.rpc.calls.time");

        counter.increment();
        timer.stop(timer.start());
        metrics.gauge("managame.rpc.calls.waiting", () -> 1).close();

        assertSame(counter, timer);
        assertSame(counter, metrics.counter("managame.rpc.calls.answered", () -> 1));
        assertEquals(0, timer.start());
    }
}
