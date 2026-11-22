package cn.managame.ai;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class AiEventPublisherTest {
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);

    public record Purchase(String item, int quantity) {}

    @Test void publishesEnvelopesWithPartitionKeyIdTimestampAndLingerBatching() throws Exception {
        var batches = new CopyOnWriteArrayList<List<AiEvent>>();
        var received = new CountDownLatch(1);
        var seq = new AtomicInteger();
        var metrics = new RecordingMetrics();
        try (var publisher = AiEventPublisher.builder(batch -> {
                    batches.add(batch);
                    received.countDown();
                })
                .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
                .idGenerator(() -> "evt-" + seq.incrementAndGet())
                .clock(() -> 1_700_000_000_000L)
                .limits(1, 32, 3, Duration.ofSeconds(5))
                .metrics(metrics)
                .build()) {
            Metadata meta = Metadata.builder().put(TRACE, "t-1").build();
            assertTrue(publisher.publish("role:42", meta, new Purchase("boots", 1)));
            assertTrue(publisher.publish("role:42", meta, new Purchase("sword", 2)));
            assertTrue(publisher.publish("role:99", meta, new Purchase("helm", 1)));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> publisher.publish("unregistered"));
        }
        assertEquals(1, batches.size());
        List<AiEvent> first = batches.getFirst();
        assertEquals(3, first.size());
        assertEquals("evt-1", first.get(0).eventId());
        assertEquals("purchase.completed", first.get(0).event());
        assertEquals("role:42", first.get(0).partitionKey());
        assertEquals(1_700_000_000_000L, first.get(0).occurredAt());
        assertEquals("t-1", first.get(0).metadata().get(TRACE));
        assertEquals("{\"item\":\"boots\",\"quantity\":1}", first.get(0).payload());
        assertEquals(3, metrics.count("managame.ai.events.sent"));
        assertEquals(1, metrics.count("managame.ai.events.batches"));
        assertEquals(0, metrics.openMeters());
    }

    @Test void respectsMaxBatchBytesAndRetriesTransientFailuresWithErrorHandlerFallback() throws Exception {
        var batches = new CopyOnWriteArrayList<List<AiEvent>>();
        var failures = new CopyOnWriteArrayList<AiEventFailure>();
        var attempts = new AtomicInteger();
        var metrics = new RecordingMetrics();
        try (var publisher = AiEventPublisher.builder(batch -> {
                    if (attempts.incrementAndGet() == 1) throw new RuntimeException("transient MQ blip");
                    batches.add(batch);
                })
                .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
                .limits(1, 32, 10, Duration.ofMillis(50))
                .maxBatchBytes(50) // each Purchase JSON is ~31 bytes, so at most 1 per batch!
                .retry(2, Duration.ofMillis(10))
                .errorHandler(failures::add)
                .metrics(metrics)
                .build()) {
            assertTrue(publisher.publish("role:1", new Purchase("long-item-name-1", 1)));
            assertTrue(publisher.publish("role:1", new Purchase("long-item-name-2", 2)));
        }
        // Transient failure on attempt 1 recovered on retry without losing any event
        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).size());
        assertEquals(1, batches.get(1).size());
        assertEquals(1, metrics.count("managame.ai.events.retried"));
        assertEquals(2, metrics.count("managame.ai.events.sent"));
        assertTrue(failures.isEmpty());

        // Non-retryable error skips retries and reports directly to errorHandler
        var nonRetryableFailures = new CopyOnWriteArrayList<AiEventFailure>();
        var nonRetryAttempts = new AtomicInteger();
        try (var publisher = AiEventPublisher.builder(batch -> {
                    nonRetryAttempts.incrementAndGet();
                    throw new IllegalArgumentException("bad schema");
                })
                .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
                .limits(1, 16, 10, Duration.ZERO)
                .retry(3, Duration.ofMillis(10), e -> !(e instanceof IllegalArgumentException))
                .errorHandler(nonRetryableFailures::add)
                .build()) {
            assertTrue(publisher.publish(new Purchase("x", 1)));
        }
        assertEquals(1, nonRetryAttempts.get());
        assertEquals(1, nonRetryableFailures.size());
        assertEquals("purchase.completed", nonRetryableFailures.getFirst().event());
    }

    @Test void lingerWindowFlushesPartialBatchAndCloseWakesLingerImmediately() throws Exception {
        var flushed = new CopyOnWriteArrayList<List<AiEvent>>();
        var firstBatch = new CountDownLatch(1);
        try (var publisher = AiEventPublisher.builder(batch -> {
                    flushed.add(batch);
                    firstBatch.countDown();
                })
                .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
                .limits(1, 32, 50, Duration.ofMillis(80))
                .build()) {
            assertTrue(publisher.publish("role:1", new Purchase("potion", 1)));
            assertTrue(firstBatch.await(2, TimeUnit.SECONDS));
            assertEquals(1, flushed.getFirst().size());
        }

        var closedBatch = new CopyOnWriteArrayList<List<AiEvent>>();
        long t0 = System.nanoTime();
        try (var publisher = AiEventPublisher.builder(closedBatch::add)
                .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
                .limits(1, 32, 50, Duration.ofSeconds(10))
                .build()) {
            assertTrue(publisher.publish("role:2", new Purchase("ring", 1)));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(elapsedMs < 2000, "close waited for linger: " + elapsedMs + "ms");
        assertEquals(1, closedBatch.size());
        assertEquals(1, closedBatch.getFirst().size());
    }

    @Test void dropsWhenQueueFullAndRespectsShutdownTimeout() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var failures = new CopyOnWriteArrayList<AiEventFailure>();
        var metrics = new RecordingMetrics();
        var publisher = AiEventPublisher.builder(batch -> {
                    entered.countDown();
                    try { Thread.sleep(Duration.ofMinutes(1)); }
                    catch (InterruptedException e) { interrupted.set(true); throw e; }
                })
                .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
                .limits(1, 1, 1, Duration.ZERO)
                .retry(0, Duration.ZERO)
                .shutdownTimeout(Duration.ofMillis(120))
                .errorHandler(failures::add)
                .metrics(metrics)
                .build();

        assertTrue(publisher.publish(new Purchase("a", 1)));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertTrue(publisher.publish(new Purchase("b", 2)));
        assertFalse(publisher.publish(new Purchase("overflow", 3)));

        long t0 = System.nanoTime();
        publisher.close();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000);
        assertTrue(interrupted.get());
        assertFalse(publisher.publish(new Purchase("after-close", 4)));
        assertTrue(metrics.count("managame.ai.events.lost") >= 3);
        assertTrue(failures.size() >= 3);
        assertEquals(0, metrics.openMeters());
    }

    private static final class RecordingMetrics implements Metrics {
        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        private final AtomicInteger open = new AtomicInteger();

        @Override public Counter counter(String name, String... tags) {
            return counters.computeIfAbsent(name, k -> new LongAdder())::add;
        }
        @Override public Meter counter(String name, LongSupplier count, String... tags) {
            open.incrementAndGet();
            return open::decrementAndGet;
        }
        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            open.incrementAndGet();
            return open::decrementAndGet;
        }
        long count(String name) {
            LongAdder adder = counters.get(name);
            return adder != null ? adder.sum() : 0L;
        }
        int openMeters() { return open.get(); }
    }
}
