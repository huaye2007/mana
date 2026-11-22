package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_FAILED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static org.junit.jupiter.api.Assertions.*;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class AiClientTest {
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);

    public record Choice(@AiParam("Selected action") String selected) {}
    public record Chunk(String token) {}

    @Test void callPassesMetadataAndResponseSchemaAndIsolatesLanes() throws Exception {
        var chatStarted = new CountDownLatch(1);
        var releaseChat = new CountDownLatch(1);
        var capturedRequest = new CompletableFuture<AiRequest>();

        AiTransport transport = new AiTransport() {
            @Override public String call(AiRequest request, Duration remaining) throws Exception {
                if ("chat".equals(request.lane())) {
                    chatStarted.countDown();
                    releaseChat.await();
                    return "{\"selected\":\"chat-done\"}";
                }
                capturedRequest.complete(request);
                return "{\"selected\":\"wave\"}";
            }
        };

        try (var client = AiClient.builder(transport)
                .lane("decision", 2, 4)
                .lane("chat", 1, 1)
                .build()) {
            // Saturate chat lane (1 worker + 1 queued)
            var chat1 = client.call("chat", "npcChat", Metadata.empty(), Map.of("q", "hi"), Choice.class, Duration.ofSeconds(5));
            assertTrue(chatStarted.await(3, TimeUnit.SECONDS));
            var chat2 = client.call("chat", "npcChat", Metadata.empty(), Map.of("q", "hi2"), Choice.class, Duration.ofSeconds(5));
            var chat3 = client.call("chat", "npcChat", Metadata.empty(), Map.of("q", "hi3"), Choice.class, Duration.ofSeconds(5));
            assertEquals(AI_OVERLOADED, assertInstanceOf(AiException.class,
                    assertThrows(CompletionException.class, chat3::join).getCause()).errorCode());

            // Decision lane remains completely unaffected while chat lane is saturated
            Metadata meta = Metadata.builder().put(TRACE, "trace-99").build();
            var decision = client.call("decision", "chooseNpcMove", meta, List.of("wait", "wave"), Choice.class, Duration.ofSeconds(2));
            assertEquals(new Choice("wave"), decision.get(2, TimeUnit.SECONDS));

            AiRequest req = capturedRequest.get(1, TimeUnit.SECONDS);
            assertEquals("decision", req.lane());
            assertEquals("chooseNpcMove", req.operation());
            assertEquals("trace-99", req.metadata().get(TRACE));
            assertNotNull(req.responseSchema());
            assertTrue(req.responseSchema().contains("Selected action"));
            assertTrue(req.responseSchema().contains("\"required\":[\"selected\"]"));

            releaseChat.countDown();
            assertEquals(new Choice("chat-done"), chat1.get(3, TimeUnit.SECONDS));
            assertEquals(new Choice("chat-done"), chat2.get(3, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,
                    () -> client.call("unknown", "op", Metadata.empty(), 1, Choice.class, Duration.ofSeconds(1)));
        }
    }

    @Test void deadlineExpiresWhileQueuedOverloadAndFatalErrorsReportMetrics() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AiTransport transport = (request, remaining) -> {
            if ("error".equals(request.operation())) throw new AssertionError("boom");
            if ("bad-json".equals(request.operation())) return "not-json";
            entered.countDown();
            release.await();
            return "1";
        };
        var metrics = new RecordingMetrics();
        try (var client = AiClient.builder(transport).defaultLane(1, 1).metrics(metrics).build()) {
            var slow = client.call("slow", 1, Integer.class, Duration.ofSeconds(8));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var queued = client.call("queued", 1, Integer.class, Duration.ofMillis(180));
                var refused = client.call("full", 1, Integer.class, Duration.ofSeconds(2));
                assertEquals(AI_OVERLOADED, assertInstanceOf(AiException.class,
                        assertThrows(CompletionException.class, refused::join).getCause()).errorCode());
                assertEquals(AI_TIMEOUT, assertInstanceOf(AiException.class,
                        assertThrows(CompletionException.class, queued::join).getCause()).errorCode());
            } finally { release.countDown(); }
            assertEquals(1, slow.join());

            var err = assertInstanceOf(AiException.class, assertThrows(CompletionException.class,
                    () -> client.call("error", 1, Integer.class, Duration.ofSeconds(3)).join()).getCause());
            assertEquals(AI_FAILED, err.errorCode());
            assertInstanceOf(AssertionError.class, err.getCause());

            var proto = assertInstanceOf(AiException.class, assertThrows(CompletionException.class,
                    () -> client.call("bad-json", 1, Choice.class, Duration.ofSeconds(3)).join()).getCause());
            assertEquals(AI_PROTOCOL_ERROR, proto.errorCode());
        }
        assertEquals(1, metrics.count("managame.ai.client.succeeded[lane=default]"));
        assertEquals(1, metrics.count("managame.ai.client.refused[lane=default]"));
        assertEquals(3, metrics.count("managame.ai.client.failed[lane=default]"));
        assertTrue(metrics.count("managame.ai.client.latency_bucket[lane=default,le=+Inf]") >= 5);
        assertEquals(0, metrics.openMeters());
    }

    @Test void asyncTransportRespectsLaneLimitsWithoutBlockingWorkers() throws Exception {
        var inFlight = new AtomicInteger();
        var pending = new CompletableFuture<String>();
        AiTransport asyncTransport = new AiTransport() {
            @Override public String call(AiRequest request, Duration remaining) { throw new AssertionError("blocking call not expected"); }
            @Override public CompletionStage<String> callAsync(AiRequest request, Duration remaining) {
                inFlight.incrementAndGet();
                return pending;
            }
        };
        try (var client = AiClient.builder(asyncTransport).defaultLane(1, 1).build()) {
            var first = client.call("op", 1, Choice.class, Duration.ofSeconds(3));
            var second = client.call("op", 2, Choice.class, Duration.ofSeconds(3));
            var third = client.call("op", 3, Choice.class, Duration.ofSeconds(3));
            assertEquals(AI_OVERLOADED, assertInstanceOf(AiException.class,
                    assertThrows(CompletionException.class, third::join).getCause()).errorCode());
            pending.complete("{\"selected\":\"go\"}");
            assertEquals(new Choice("go"), first.get(3, TimeUnit.SECONDS));
            assertEquals(new Choice("go"), second.get(3, TimeUnit.SECONDS));
            assertEquals(2, inFlight.get());
        }
    }

    @Test void streamSupportsIdleTimeoutRefreshStallDetectionAndCancellation() throws Exception {
        var cancelInterrupted = new AtomicBoolean();
        var cancelStarted = new CountDownLatch(1);
        AiTransport transport = new AiTransport() {
            @Override public String call(AiRequest request, Duration remaining) { throw new AssertionError(); }
            @Override public void stream(AiRequest request, Duration remaining, Consumer<String> onChunk) throws Exception {
                switch (request.operation()) {
                    case "steady" -> {
                        // Total duration (~270ms) exceeds idleTimeout (150ms), but chunks keep arriving every 90ms
                        for (String token : List.of("Hi", " there", "!")) {
                            Thread.sleep(90);
                            onChunk.accept("{\"token\":\"" + token + "\"}");
                        }
                    }
                    case "stall" -> {
                        onChunk.accept("{\"token\":\"first\"}");
                        Thread.sleep(Duration.ofSeconds(5));
                    }
                    case "cancel" -> {
                        cancelStarted.countDown();
                        try { Thread.sleep(Duration.ofSeconds(10)); }
                        catch (InterruptedException e) { cancelInterrupted.set(true); throw e; }
                    }
                    default -> throw new IllegalArgumentException(request.operation());
                }
            }
        };

        try (var client = AiClient.builder(transport).lane("stream", 4, 8).build()) {
            // 1. Steady stream exceeds idleTimeout in total length, succeeds because chunks refresh idle clock
            var tokens = new ArrayList<String>();
            client.stream("stream", "steady", Metadata.empty(), Map.of(), Chunk.class,
                    Duration.ofMillis(180), Duration.ofSeconds(3), c -> tokens.add(c.token()))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(List.of("Hi", " there", "!"), tokens);

            // 2. Stalled stream fails at idleTimeout (~150ms), well before 10s totalTimeout
            long t0 = System.nanoTime();
            var stallFuture = client.stream("stream", "stall", Metadata.empty(), Map.of(), Chunk.class,
                    Duration.ofMillis(150), Duration.ofSeconds(10), c -> {});
            var stallEx = assertInstanceOf(AiException.class, assertThrows(CompletionException.class, stallFuture::join).getCause());
            assertEquals(AI_TIMEOUT, stallEx.errorCode());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000);

            // 3. Caller cancellation interrupts running stream immediately
            var cancelFuture = client.stream("stream", "cancel", Metadata.empty(), Map.of(), Chunk.class,
                    Duration.ofSeconds(5), Duration.ofSeconds(10), c -> {});
            assertTrue(cancelStarted.await(3, TimeUnit.SECONDS));
            assertTrue(cancelFuture.cancel(true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!cancelInterrupted.get() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(cancelInterrupted.get());
        }
    }

    @Test void closeRespectsShutdownTimeoutAndInterruptsStuckWorkers() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        AiTransport stuck = (request, remaining) -> {
            started.countDown();
            try { Thread.sleep(Duration.ofMinutes(1)); }
            catch (InterruptedException e) { interrupted.set(true); throw e; }
            return "1";
        };
        var client = AiClient.builder(stuck).shutdownTimeout(Duration.ofMillis(120)).build();
        var pending = client.call("hang", 1, Integer.class, Duration.ofSeconds(30));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        client.close();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000);
        assertEquals(AI_CLOSED, assertInstanceOf(AiException.class,
                assertThrows(CompletionException.class, pending::join).getCause()).errorCode());
        assertTrue(interrupted.get());

        var afterClose = client.call("after", 1, Integer.class, Duration.ofSeconds(1));
        assertEquals(AI_CLOSED, assertInstanceOf(AiException.class,
                assertThrows(CompletionException.class, afterClose::join).getCause()).errorCode());
    }

    private static final class RecordingMetrics implements Metrics {
        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        private final Map<String, LongSupplier> lazyCounters = new ConcurrentHashMap<>();
        private final AtomicInteger open = new AtomicInteger();

        @Override public Counter counter(String name, String... tags) {
            return counters.computeIfAbsent(key(name, tags), k -> new LongAdder())::add;
        }
        @Override public Meter counter(String name, LongSupplier count, String... tags) {
            lazyCounters.put(key(name, tags), count);
            open.incrementAndGet();
            return open::decrementAndGet;
        }
        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            open.incrementAndGet();
            return open::decrementAndGet;
        }
        long count(String key) {
            LongAdder adder = counters.get(key);
            if (adder != null) return adder.sum();
            LongSupplier supplier = lazyCounters.get(key);
            return supplier != null ? supplier.getAsLong() : 0L;
        }
        int openMeters() { return open.get(); }
        private static String key(String name, String... tags) {
            if (tags.length == 0) return name;
            StringBuilder sb = new StringBuilder(name).append('[');
            for (int i = 0; i < tags.length; i += 2) {
                if (i > 0) sb.append(',');
                sb.append(tags[i]).append('=').append(tags[i + 1]);
            }
            return sb.append(']').toString();
        }
    }
}
