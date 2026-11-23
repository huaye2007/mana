package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CANCELLED;
import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_UNAVAILABLE;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.ai.AiToolsTest.TestMetrics;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiClientTest {
    record ChatReq(long npcId, String text) {}
    record ChatReply(String reply, String headerEcho) {}
    record GameEvent(String kind, long roleId) {}

    private HttpServer server;
    private int port;
    private final AtomicReference<CountDownLatch> slowGate = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> streamRelease = new AtomicReference<>();
    private final List<String> receivedNotifies = new CopyOnWriteArrayList<>();

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        port = server.getAddress().getPort();

        server.createContext("/api/chat", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(reqBytes, StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String trace = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            String json = "{\"reply\":\"echo:" + body.replace("\"", "'") + "\",\"headerEcho\":\"" + auth + "|" + trace + "\"}";
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });

        server.createContext("/api/bad-json", exchange -> {
            byte[] out = "not-valid-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });

        server.createContext("/api/error", exchange -> {
            byte[] out = "upstream overloaded".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });

        server.createContext("/api/slow", exchange -> {
            CountDownLatch gate = slowGate.get();
            if (gate != null) {
                try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
            byte[] out = "{\"reply\":\"late\",\"headerEcho\":\"\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });

        server.createContext("/api/notify", exchange -> {
            receivedNotifies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        server.createContext("/api/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("id: 1\nevent: delta\ndata: Hello \n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                CountDownLatch gate = streamRelease.get();
                if (gate != null) {
                    try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                }
                os.write("id: 2\nevent: delta\ndata: world\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("event: done\ndata: ok\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception ignored) {
                // Client cancelled or disconnected mid-stream
            }
        });

        server.start();
    }

    @AfterEach void stopServer() {
        CountDownLatch g1 = slowGate.get();
        if (g1 != null) g1.countDown();
        CountDownLatch g2 = streamRelease.get();
        if (g2 != null) g2.countDown();
        if (server != null) server.stop(0);
    }

    @Test void unaryCallHeadersInterceptorsAndOffThreadCallback() throws Exception {
        TestMetrics metrics = new TestMetrics();
        List<String> afterLog = new CopyOnWriteArrayList<>();
        AiClientInterceptor trace = new AiClientInterceptor() {
            @Override public AiRequest before(AiRequest request) {
                return request.withHeader("X-Trace-Id", "trace-99");
            }

            @Override public void after(AiRequest request, int errorCode, String message) {
                afterLog.add(request.path() + ":" + errorCode);
            }
        };

        try (AiClient client = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/api")
                .codec(new JacksonAiCodec())
                .header("Authorization", "Bearer secret")
                .intercept(List.of(trace))
                .metrics(metrics, "npc")
                .build()) {

            long callerThread = Thread.currentThread().threadId();
            AtomicReference<Long> callbackThread = new AtomicReference<>();
            CompletableFuture<AiResult<ChatReply>> future = new CompletableFuture<>();

            client.call("/chat", new ChatReq(7, "hi"), ChatReply.class, result -> {
                callbackThread.set(Thread.currentThread().threadId());
                future.complete(result);
            });

            AiResult<ChatReply> result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.isSuccess());
            assertTrue(result.data().reply().contains("hi"));
            assertEquals("Bearer secret|trace-99", result.data().headerEcho());
            assertNotEquals(callerThread, callbackThread.get());
            assertEquals(0, client.inFlight());
            assertEquals(List.of("/chat:0"), afterLog);
            assertEquals(1L, metrics.count("managame.ai.client.calls", "client", "npc", "kind", "call"));
            assertEquals(0.0, metrics.gaugeValue("managame.ai.client.in_flight", "client", "npc"));
        }
    }

    @Test void streamingReceivesIncrementalChunksAndCompletes() throws Exception {
        TestMetrics metrics = new TestMetrics();
        CountDownLatch firstChunkArrived = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        streamRelease.set(gate);

        try (AiClient client = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/api")
                .codec(new JacksonAiCodec())
                .metrics(metrics)
                .build()) {

            List<AiChunk> chunks = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> completed = new CompletableFuture<>();
            client.stream("/stream", new ChatReq(1, "story"), new AiStreamListener() {
                @Override public void onChunk(AiChunk chunk) {
                    chunks.add(chunk);
                    firstChunkArrived.countDown();
                }

                @Override public void onComplete() { completed.complete(null); }
                @Override public void onError(int code, String msg) { completed.completeExceptionally(new AiException(code, msg)); }
            });

            // Chunk 1 arrives while server is still holding chunk 2 -> proves true incremental streaming
            assertTrue(firstChunkArrived.await(5, TimeUnit.SECONDS));
            assertEquals(1, chunks.size());
            assertEquals(new AiChunk("delta", "Hello ", "1"), chunks.get(0));

            gate.countDown();
            completed.get(5, TimeUnit.SECONDS);
            assertEquals(3, chunks.size());
            assertEquals(new AiChunk("delta", "world", "2"), chunks.get(1));
            assertEquals(new AiChunk("done", "ok", "2"), chunks.get(2));
            assertEquals(0, client.inFlight());
            assertEquals(3L, metrics.count("managame.ai.client.stream_chunks"));
        }
    }

    @Test void cancelMidStreamDeliversCancelledOnceAndFreesSlot() throws Exception {
        CountDownLatch firstChunkArrived = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        streamRelease.set(gate);

        try (AiClient client = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/api")
                .codec(new JacksonAiCodec())
                .build()) {

            AtomicInteger terminalCount = new AtomicInteger();
            CompletableFuture<Integer> errorCode = new CompletableFuture<>();
            AiCall call = client.stream("/stream", new ChatReq(1, "cancel-me"), new AiStreamListener() {
                @Override public void onChunk(AiChunk chunk) { firstChunkArrived.countDown(); }
                @Override public void onComplete() { terminalCount.incrementAndGet(); }
                @Override public void onError(int code, String msg) {
                    terminalCount.incrementAndGet();
                    errorCode.complete(code);
                }
            });

            assertTrue(firstChunkArrived.await(5, TimeUnit.SECONDS));
            assertTrue(call.cancel());
            assertFalse(call.cancel()); // idempotent

            assertEquals(AI_CANCELLED, errorCode.get(5, TimeUnit.SECONDS));
            assertTrue(call.isCancelled());
            assertTrue(call.isDone());
            assertEquals(0, client.inFlight());
            gate.countDown();
            Thread.sleep(50);
            assertEquals(1, terminalCount.get());
        }
    }

    @Test void timeoutOverloadErrorCodesNotifyInterceptorRejectAndClose() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        slowGate.set(gate);

        try (AiClient client = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/api")
                .codec(new JacksonAiCodec())
                .timeout(Duration.ofSeconds(5))
                .maxInFlight(1)
                .build()) {

            // Unary timeout
            CompletableFuture<AiResult<ChatReply>> timedOut = new CompletableFuture<>();
            client.call(AiRequest.of("/slow", new ChatReq(1, "slow")).withTimeout(Duration.ofMillis(120)),
                    ChatReply.class, timedOut::complete);
            assertEquals(AI_TIMEOUT, timedOut.get(5, TimeUnit.SECONDS).code());
            assertEquals(0, client.inFlight());

            // Non-2xx -> AI_UNAVAILABLE
            CompletableFuture<AiResult<ChatReply>> err503 = new CompletableFuture<>();
            client.call("/error", new ChatReq(1, "x"), ChatReply.class, err503::complete);
            AiResult<ChatReply> r503 = err503.get(5, TimeUnit.SECONDS);
            assertEquals(AI_UNAVAILABLE, r503.code());
            assertTrue(r503.message().contains("503"));

            // Malformed response JSON -> AI_PROTOCOL_ERROR
            CompletableFuture<AiResult<ChatReply>> badJson = new CompletableFuture<>();
            client.call("/bad-json", new ChatReq(1, "x"), ChatReply.class, badJson::complete);
            assertEquals(AI_PROTOCOL_ERROR, badJson.get(5, TimeUnit.SECONDS).code());

            // Notify succeeds
            CompletableFuture<AiResult<Void>> notified = new CompletableFuture<>();
            client.notify(AiRequest.of("/notify", new GameEvent("boss_killed", 1001)), notified::complete);
            assertEquals(SUCCESS, notified.get(5, TimeUnit.SECONDS).code());
            assertEquals(1, receivedNotifies.size());
            assertTrue(receivedNotifies.get(0).contains("boss_killed"));

            // Overload when maxInFlight=1 is occupied
            CompletableFuture<AiResult<ChatReply>> slowFirst = new CompletableFuture<>();
            AiCall firstCall = client.call("/slow", new ChatReq(1, "hold"), ChatReply.class, slowFirst::complete);
            CompletableFuture<AiResult<ChatReply>> second = new CompletableFuture<>();
            client.call("/chat", new ChatReq(2, "overflow"), ChatReply.class, second::complete);
            assertEquals(AI_OVERLOADED, second.get(5, TimeUnit.SECONDS).code());

            // Close cancels in-flight call and refuses subsequent calls with AI_CLOSED
            client.close();
            assertEquals(AI_CANCELLED, slowFirst.get(5, TimeUnit.SECONDS).code());
            assertTrue(firstCall.isDone());

            CompletableFuture<AiResult<ChatReply>> afterClose = new CompletableFuture<>();
            client.call("/chat", new ChatReq(3, "closed"), ChatReply.class, afterClose::complete);
            assertEquals(AI_CLOSED, afterClose.get(5, TimeUnit.SECONDS).code());
        } finally {
            gate.countDown();
        }
    }

    @Test void streamTimesOutWhenServerStalls() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        streamRelease.set(gate);

        try (AiClient client = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/api")
                .timeout(Duration.ofMillis(150))
                .build()) {

            CompletableFuture<Integer> errCode = new CompletableFuture<>();
            client.stream("/stream", null, new AiStreamListener() {
                @Override public void onChunk(AiChunk chunk) {}
                @Override public void onError(int code, String message) { errCode.complete(code); }
            });
            assertEquals(AI_TIMEOUT, errCode.get(5, TimeUnit.SECONDS));
            assertEquals(0, client.inFlight());
        } finally {
            gate.countDown();
        }
    }

    @Test void interceptorCanRejectCallWithoutHittingTransport() throws Exception {
        try (AiClient client = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/api")
                .intercept(List.of(new AiClientInterceptor() {
                    @Override public AiRequest before(AiRequest request) { return null; }
                }))
                .build()) {
            CompletableFuture<AiResult<String>> res = new CompletableFuture<>();
            AiCall call = client.call("/chat", "hi", String.class, res::complete);
            assertEquals(AI_CANCELLED, res.get(5, TimeUnit.SECONDS).code());
            assertTrue(call.isCancelled());
            assertEquals(0, client.inFlight());
        }
    }

    @Test void sharedHttpClientStaysOpenAfterAiClientCloses() throws Exception {
        try (java.net.http.HttpClient shared = java.net.http.HttpClient.newHttpClient()) {
            AiClient first = AiClient.builder()
                    .baseUrl("http://127.0.0.1:" + port + "/api")
                    .httpClient(shared)
                    .codec(new JacksonAiCodec())
                    .build();
            first.close();

            try (AiClient second = AiClient.builder()
                    .baseUrl("http://127.0.0.1:" + port + "/api")
                    .httpClient(shared)
                    .codec(new JacksonAiCodec())
                    .build()) {
                CompletableFuture<AiResult<ChatReply>> res = new CompletableFuture<>();
                second.call("/chat", new ChatReq(5, "still-works"), ChatReply.class, res::complete);
                assertTrue(res.get(5, TimeUnit.SECONDS).isSuccess());
            }
        }
    }

    @Test void concurrentCallsMaintainInFlightAccounting() throws Exception {
        try (AiClient client = AiClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/api")
                .codec(new JacksonAiCodec())
                .maxInFlight(32)
                .build()) {
            int total = 32;
            CountDownLatch done = new CountDownLatch(total);
            AtomicInteger ok = new AtomicInteger();
            for (int i = 0; i < total; i++) {
                int idx = i;
                client.call("/chat", new ChatReq(idx, "msg-" + idx), ChatReply.class, r -> {
                    if (r.isSuccess()) ok.incrementAndGet();
                    done.countDown();
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(total, ok.get());
            assertEquals(0, client.inFlight());
        }
    }
}
