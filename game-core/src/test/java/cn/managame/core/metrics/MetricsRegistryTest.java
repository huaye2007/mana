package cn.managame.core.metrics;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricsRegistryTest {
    /** Records what a collector would see, as "kind name=value". */
    static final class Recorder implements MetricSink {
        final List<String> readings = new ArrayList<>();
        @Override public void gauge(String name, long value) { readings.add("gauge " + name + "=" + value); }
        @Override public void counter(String name, long value) { readings.add("counter " + name + "=" + value); }
    }

    @Test void readsEverySourceInPrefixOrderWithItsPrefixApplied() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.publish("rpc", sink -> { sink.gauge("calls.pending", 3); sink.counter("calls.timedout", 7); });
        registry.publish("network.client", sink -> sink.gauge("connections", 42));
        assertEquals(List.of("network.client", "rpc"), registry.prefixes());
        Recorder recorder = new Recorder();
        registry.read(recorder);
        assertEquals(List.of("gauge network.client.connections=42",
                "gauge rpc.calls.pending=3", "counter rpc.calls.timedout=7"), recorder.readings);
    }
    @Test void republishingAPrefixReplacesItsSourceAndWithdrawalRemovesIt() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.publish("data", sink -> sink.gauge("pending", 1));
        registry.publish("data", sink -> sink.gauge("pending", 2));
        assertEquals(List.of("data"), registry.prefixes());
        Recorder second = new Recorder();
        registry.read(second);
        assertEquals(List.of("gauge data.pending=2"), second.readings);
        assertTrue(registry.withdraw("data"));
        assertFalse(registry.withdraw("data"));
        Recorder empty = new Recorder();
        registry.read(empty);
        assertEquals(List.of(), empty.readings);
        assertEquals(List.of(), registry.prefixes());
    }
    @Test void prefixesAndMetricNamesMustBeLowercaseDottedPaths() {
        MetricsRegistry registry = new MetricsRegistry();
        for (String invalid : new String[]{"", ".", "a.", ".a", "a..b", "Rpc", "rpc_calls", "1rpc", "rpc.1a", "rpc calls"})
            assertThrows(IllegalArgumentException.class, () -> registry.publish(invalid, sink -> {}),
                    "accepted invalid prefix: " + invalid);
        assertThrows(NullPointerException.class, () -> registry.publish("rpc", null));
        assertThrows(NullPointerException.class, () -> registry.publish(null, sink -> {}));
        // A digit after the first character of a segment is fine; so is a single segment.
        registry.publish("rpc", sink -> sink.gauge("slot0.pending", 1));
        Recorder recorder = new Recorder();
        registry.read(recorder);
        assertEquals(List.of("gauge rpc.slot0.pending=1"), recorder.readings);
        // The same rule applies to what a source reports, and it is reported to the component's author.
        registry.publish("rpc", sink -> sink.counter("Calls", 1));
        assertThrows(IllegalArgumentException.class, () -> registry.read(new Recorder()));
    }
    @Test void aPrefixedNameMustStillFitTheLengthBound() {
        MetricsRegistry registry = new MetricsRegistry();
        String longest = "a".repeat(MetricsRegistry.MAX_NAME_LENGTH);
        registry.publish(longest, sink -> {});                       // a prefix may use the whole bound
        assertThrows(IllegalArgumentException.class, () -> registry.publish(longest + "a", sink -> {}));
        registry.publish("rpc", sink -> sink.gauge(longest, 1));     // but the prefixed name may not exceed it
        assertThrows(IllegalArgumentException.class, () -> registry.read(new Recorder()));
    }
    @Test void aSourceThatThrowsEndsTheCollection() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.publish("aaa", sink -> sink.gauge("ok", 1));
        registry.publish("bbb", sink -> { throw new IllegalStateException("broken source"); });
        registry.publish("ccc", sink -> sink.gauge("ok", 2));
        Recorder recorder = new Recorder();
        assertThrows(IllegalStateException.class, () -> registry.read(recorder));
        // The documented consequence: readings before the failure arrived, later sources were not read.
        assertEquals(List.of("gauge aaa.ok=1"), recorder.readings);
        assertThrows(NullPointerException.class, () -> registry.read(null));
    }
}
