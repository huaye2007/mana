package com.github.huaye2007.mana.jpa.core.metrics;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

public class DefaultMetricsCollectorTest {

    @Test
    public void recordsCountsLatencyErrorsAndGauges() {
        DefaultMetricsCollector metrics = new DefaultMetricsCollector();

        metrics.recordLatency("load", "player", 12);
        metrics.recordLatency("load", "player", 8);
        metrics.recordCount("load", "player", 3);
        metrics.recordError("load", "player", new IllegalStateException("failed"));
        metrics.recordGauge("queue.size", "player", 9);

        assertEquals(3, metrics.getCount("load", "player"));
        assertEquals(2, metrics.getInvocationCount("load", "player"));
        assertEquals(3, metrics.getItemCount("load", "player"));
        assertEquals(20, metrics.getTotalLatency("load", "player"));
        assertEquals(1, metrics.getErrorCount("load", "player"));
        assertEquals(9, metrics.getGauge("queue.size", "player"));
        assertEquals(0, metrics.getGauge("queue.size", "missing"));
    }

    @Test
    public void countRecordingIsThreadSafe() throws Exception {
        DefaultMetricsCollector metrics = new DefaultMetricsCollector();
        int threads = 4;
        int iterations = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        metrics.recordCount("write", "player", 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        assertEquals((long) threads * iterations, metrics.getCount("write", "player"));
    }
}
