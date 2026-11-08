package cn.managame.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LatencyBucketsTest {
    @Test void aDurationCountsInEveryBucketItFitsAndABoundIsInclusive() {
        LatencyBuckets latency = new LatencyBuckets();
        latency.record(500_000);        // exactly half a millisecond
        latency.record(500_001);        // just over it
        latency.record(9_000_000_000L); // beyond the last bound

        assertEquals(1, latency.within(0), "le 0.5");
        assertEquals(2, latency.within(1), "le 1");
        assertEquals(2, latency.within(6), "le 500");
        assertEquals(3, latency.within(7), "le +Inf counts them all");
        assertEquals(9_001_000_001L, latency.totalNanos());
    }

    @Test void itReportsABucketPerBoundAndTheTotalWithTheTagsGiven() {
        MetricsTest.MemoryMetrics metrics = new MetricsTest.MemoryMetrics();
        LatencyBuckets latency = new LatencyBuckets();
        latency.report(metrics, "managame.test.wait", "domain", "1");
        latency.record(2_000_000);

        assertEquals(0, metrics.read("managame.test.wait_bucket[domain, 1, le, 1]"));
        assertEquals(1, metrics.read("managame.test.wait_bucket[domain, 1, le, 5]"));
        assertEquals(1, metrics.read("managame.test.wait_bucket[domain, 1, le, +Inf]"));
        assertEquals(2_000_000, metrics.read("managame.test.wait_nanos[domain, 1]"));
    }
}
