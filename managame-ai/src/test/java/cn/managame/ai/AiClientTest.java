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

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiClientTest {
    private static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(1);

    record ChatReq(long roleId, String text) {}
    record ChatRes(String reply, String trace, String auth) {}

    private static final AiCodec JACKSON = new AiCodec() {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
        @Override public Object decode(String json, Type type) throws Exception {
            return mapper.readValue(json, mapper.constructType(type));
        }
    };

    private NetworkServer httpServer;
    private EventLoopGroup sseGroup;
    private Channel sseChannel;
    private AiClient client;

    @AfterEach void tearDown() {
        if (client != null) client.close();
        if (httpServer != null) httpServer.close();
        if (sseChannel != null) sseChannel.close().syncUninterruptibly();
        if (sseGroup != null) sseGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test void unaryCallHeadersMetadataAndRawString() throws Exception {
        CountDownLatch notifySeen = new CountDownLatch(1);
        AtomicReference<String> notifyBody = new AtomicReference<>();

        httpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().contextPath("/api").handler(exchange -> {
                    FullHttpRequest req = exchange.request();
                    String body = req.content().toString(StandardCharsets.UTF_8);
                    String trace = req.headers().get("X-Trace-Id");
                    String auth = req.headers().get("Authorization");
                    switch (exchange.path()) {
                        case "/chat" -> {
                            String resJson = "{\"reply\":\"echo:" + body.replace("\"", "\\\"")
                                    + "\",\"trace\":\"" + trace + "\",\"auth\":\"" + auth + "\"}";
                            respond(exchange, HttpResponseStatus.OK, resJson);
                        }
                        case "/raw" -> respond(exchange, HttpResponseStatus.OK, "{\"raw\":true}");
                        case "/events/boss" -> {
                            notifyBody.set(body);
                            notifySeen.countDown();
                            exchange.respond(HttpResponseStatus.NO_CONTENT);
                        }
                        case "/busy" -> respond(exchange, HttpResponseStatus.TOO_MANY_REQUESTS, "rate limited");
                        case "/bad" -> respond(exchange, HttpResponseStatus.BAD_REQUEST, "bad input");
                        case "/down" -> respond(exchange, HttpResponseStatus.INTERNAL_SERVER_ERROR, "oops");
                        default -> exchange.respond(HttpResponseStatus.NOT_FOUND);
                    }
                }).build());

        RecordingMetrics metrics = new RecordingMetrics();
        client = AiClient.builder("http://127.0.0.1:" + httpServer.port() + "/api")
                .codec(JACKSON)
                .header("Authorization", "Bearer secret")
                .propagate(TRACE_ID, "X-Trace-Id")
                .metrics(metrics, "npc")
                .build();

        Thread callerThread = Thread.currentThread();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CompletableFuture<AiResult<ChatRes>> typed = new CompletableFuture<>();
        Metadata md = Metadata.builder().put(TRACE_ID, "trace-42").build();
        client.call(AiRequest.of("/chat", new ChatReq(7, "hello"), md), ChatRes.class, res -> {
            callbackThread.set(Thread.currentThread());
            typed.complete(res);
        });
        AiResult<ChatRes> res = typed.get(5, TimeUnit.SECONDS);
        assertTrue(res.isSuccess());
        assertEquals(200, res.status());
        assertEquals("trace-42", res.data().trace());
        assertEquals("Bearer secret", res.data().auth());
        assertNotEquals(callerThread, callbackThread.get(), "Callback must not run on caller thread");

        // String body & String response bypass Jackson codec (no double-quoting)
        CompletableFuture<AiResult<String>> raw = new CompletableFuture<>();
        client.call("/raw", "{\"hello\":1}", String.class, raw::complete);
        assertEquals("{\"raw\":true}", raw.get(5, TimeUnit.SECONDS).data());

        // Status mapping
        assertEquals(AI_OVERLOADED, callSync("/busy", "{}", String.class).code());
        assertEquals(AI_PROTOCOL_ERROR, callSync("/bad", "{}", String.class).code());
        assertEquals(AI_UNAVAILABLE, callSync("/down", "{}", String.class).code());

        // Fire-and-forget notify
        assertTrue(client.notify("/events/boss", new ChatReq(7, "boss-dead")));
        assertTrue(notifySeen.await(5, TimeUnit.SECONDS));
        assertTrue(notifyBody.get().contains("boss-dead"));

        assertEquals(5L, metrics.counterValue("managame.ai.client.calls", "client=npc,kind=call"));
        assertEquals(1L, metrics.counterValue("managame.ai.client.calls", "client=npc,kind=notify"));
        assertEquals(1L, metrics.counterValue("managame.ai.client.failures", "client=npc,code=AI_OVERLOADED"));
        assertEquals(1L, metrics.counterValue("managame.ai.client.failures", "client=npc,code=AI_PROTOCOL_ERROR"));
        assertEquals(1L, metrics.counterValue("managame.ai.client.failures", "client=npc,code=AI_UNAVAILABLE"));
    }

    @Test void overloadTimeoutCancelAndClose() throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);

        httpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().handler(exchange -> {
                    if ("/slow".equals(exchange.path())) {
                        firstArrived.countDown();
                        Thread.ofVirtual().start(() -> {
                            try {
                                releaseServer.await(5, TimeUnit.SECONDS);
                                respond(exchange, HttpResponseStatus.OK, "late");
                            } catch (InterruptedException ignored) {}
                        });
                    }
                }).build());

        client = AiClient.builder("http://127.0.0.1:" + httpServer.port())
                .maxInFlight(1)
                .timeout(Duration.ofSeconds(5))
                .build();

        // 1st fills maxInFlight=1
        CompletableFuture<AiResult<String>> first = new CompletableFuture<>();
        AiCall call1 = client.call("/slow", "{}", String.class, first::complete);
        assertTrue(firstArrived.await(5, TimeUnit.SECONDS));
        assertEquals(1, client.inFlight());

        // 2nd is rejected immediately with AI_OVERLOADED on another thread
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> rejectThread = new AtomicReference<>();
        CompletableFuture<AiResult<String>> overloaded = new CompletableFuture<>();
        client.call("/slow", "{}", String.class, r -> {
            rejectThread.set(Thread.currentThread());
            overloaded.complete(r);
        });
        assertEquals(AI_OVERLOADED, overloaded.get(5, TimeUnit.SECONDS).code());
        assertNotEquals(caller, rejectThread.get());

        // Cancel 1st
        assertTrue(call1.cancel());
        assertFalse(call1.cancel());
        assertEquals(AI_CANCELLED, first.get(5, TimeUnit.SECONDS).code());
        assertEquals(0, client.inFlight());

        // Per-request timeout
        CompletableFuture<AiResult<String>> timedOut = new CompletableFuture<>();
        client.call(AiRequest.of("/slow", "{}").withTimeout(Duration.ofMillis(120)), String.class, timedOut::complete);
        assertEquals(AI_TIMEOUT, timedOut.get(5, TimeUnit.SECONDS).code());

        // Close aborts in-flight and rejects subsequent calls with AI_CLOSED
        CompletableFuture<AiResult<String>> aborted = new CompletableFuture<>();
        client.call("/slow", "{}", String.class, aborted::complete);
        client.close();
        assertEquals(AI_CLOSED, aborted.get(5, TimeUnit.SECONDS).code());

        CompletableFuture<AiResult<String>> afterClose = new CompletableFuture<>();
        client.call("/slow", "{}", String.class, afterClose::complete);
        assertEquals(AI_CLOSED, afterClose.get(5, TimeUnit.SECONDS).code());
        assertFalse(client.notify("/slow", "{}"));
        releaseServer.countDown();
    }

    @Test void sharedHttpClientSurvivesAiClientClose() throws Exception {
        httpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().handler(ex -> respond(ex, HttpResponseStatus.OK, "pong")).build());
        try (HttpClient shared = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            AiClient a = AiClient.builder("http://127.0.0.1:" + httpServer.port()).httpClient(shared).build();
            AiClient b = AiClient.builder("http://127.0.0.1:" + httpServer.port()).httpClient(shared).build();
            a.close();
            CompletableFuture<AiResult<String>> res = new CompletableFuture<>();
            b.call("/ping", "{}", String.class, res::complete);
            assertEquals("pong", res.get(5, TimeUnit.SECONDS).data());
            b.close();
        }
    }

    @Test void incrementalSseStreamCancelAndTimeout() throws Exception {
        CountDownLatch sendSecondChunk = new CountDownLatch(1);
        CountDownLatch holdForever = new CountDownLatch(1);
        int port = startSseServer(sendSecondChunk, holdForever);

        client = AiClient.builder("http://127.0.0.1:" + port)
                .timeout(Duration.ofSeconds(5))
                .build();

        // 1. Incremental delivery: 1st event arrives BEFORE server writes 2nd event
        CountDownLatch firstEventSeen = new CountDownLatch(1);
        List<AiStreamEvent> received = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AiResult<Void>> done = new CompletableFuture<>();
        client.stream("/stream", "{}", new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) {
                received.add(event);
                firstEventSeen.countDown();
            }
            @Override public void onComplete(AiResult<Void> result) { done.complete(result); }
        });
        assertTrue(firstEventSeen.await(5, TimeUnit.SECONDS), "First SSE chunk must arrive incrementally");
        assertEquals(1, received.size());
        assertEquals(new AiStreamEvent("token", "你", "1"), received.get(0));
        assertFalse(done.isDone());
        sendSecondChunk.countDown();
        assertEquals(SUCCESS, done.get(5, TimeUnit.SECONDS).code());
        assertEquals(2, received.size());
        assertEquals(new AiStreamEvent("token", "好", "2"), received.get(1));

        // 2. Cancel mid-stream
        CountDownLatch firstHangEvent = new CountDownLatch(1);
        CompletableFuture<AiResult<Void>> cancelled = new CompletableFuture<>();
        AiCall streamCall = client.stream("/hang", "{}", new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) { firstHangEvent.countDown(); }
            @Override public void onComplete(AiResult<Void> result) { cancelled.complete(result); }
        });
        assertTrue(firstHangEvent.await(5, TimeUnit.SECONDS));
        assertTrue(streamCall.cancel());
        assertEquals(AI_CANCELLED, cancelled.get(5, TimeUnit.SECONDS).code());

        // 3. Stream total-duration timeout (even after headers and 1st chunk arrived)
        CompletableFuture<AiResult<Void>> timedOut = new CompletableFuture<>();
        client.stream(AiRequest.of("/hang", "{}").withTimeout(Duration.ofMillis(150)), new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) {}
            @Override public void onComplete(AiResult<Void> result) { timedOut.complete(result); }
        });
        assertEquals(AI_TIMEOUT, timedOut.get(5, TimeUnit.SECONDS).code());

        // 4. Non-2xx SSE error surfaces HTTP status & code cleanly
        CompletableFuture<AiResult<Void>> err = new CompletableFuture<>();
        client.stream("/rate-limit", "{}", new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) {}
            @Override public void onComplete(AiResult<Void> result) { err.complete(result); }
        });
        AiResult<Void> errRes = err.get(5, TimeUnit.SECONDS);
        assertEquals(AI_OVERLOADED, errRes.code());
        assertEquals(429, errRes.status());

        // 5. SSE idleTimeout: healthy slow trickle succeeds beyond idleTimeout; stalled stream times out promptly
        List<AiStreamEvent> trickle = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AiResult<Void>> trickleDone = new CompletableFuture<>();
        client.stream(AiRequest.of("/trickle", "{}").withTimeout(Duration.ofSeconds(5))
                .withIdleTimeout(Duration.ofMillis(250)), new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) { trickle.add(event); }
            @Override public void onComplete(AiResult<Void> result) { trickleDone.complete(result); }
        });
        assertEquals(SUCCESS, trickleDone.get(5, TimeUnit.SECONDS).code());
        assertEquals(4, trickle.size());

        CompletableFuture<AiResult<Void>> idleTimedOut = new CompletableFuture<>();
        client.stream(AiRequest.of("/hang", "{}").withTimeout(Duration.ofSeconds(5))
                .withIdleTimeout(Duration.ofMillis(150)), new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) {}
            @Override public void onComplete(AiResult<Void> result) { idleTimedOut.complete(result); }
        });
        assertEquals(AI_TIMEOUT, idleTimedOut.get(5, TimeUnit.SECONDS).code());
        holdForever.countDown();
    }

    @Test void getDeleteAndCustomHttpMethods() throws Exception {
        httpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().contextPath("/api").handler(exchange -> {
                    FullHttpRequest req = exchange.request();
                    String method = req.method().name();
                    String body = req.content().toString(StandardCharsets.UTF_8);
                    respond(exchange, HttpResponseStatus.OK, method + ":" + exchange.path() + ":" + body);
                }).build());

        client = AiClient.builder("http://127.0.0.1:" + httpServer.port() + "/api").build();

        CompletableFuture<AiResult<String>> getRes = new CompletableFuture<>();
        client.call(AiRequest.get("/jobs/42"), String.class, getRes::complete);
        assertEquals("GET:/jobs/42:", getRes.get(5, TimeUnit.SECONDS).data());

        CompletableFuture<AiResult<String>> delRes = new CompletableFuture<>();
        client.call(AiRequest.delete("/sessions/npc-1"), String.class, delRes::complete);
        assertEquals("DELETE:/sessions/npc-1:", delRes.get(5, TimeUnit.SECONDS).data());

        CompletableFuture<AiResult<String>> putRes = new CompletableFuture<>();
        client.call(AiRequest.of("/config", "{\"k\":1}").withMethod("PUT"), String.class, putRes::complete);
        assertEquals("PUT:/config:{\"k\":1}", putRes.get(5, TimeUnit.SECONDS).data());
    }

    @Test void responseBoundsSseEventBoundsAndNonDuplicateDefaultHeaders() throws Exception {
        AtomicReference<List<String>> seenAcceptHeaders = new AtomicReference<>();
        httpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().handler(exchange -> {
                    seenAcceptHeaders.set(exchange.request().headers().getAll(HttpHeaderNames.ACCEPT));
                    if ("/huge".equals(exchange.path())) {
                        respond(exchange, HttpResponseStatus.OK, "x".repeat(256));
                    } else if ("/huge-sse".equals(exchange.path())) {
                        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                Unpooled.copiedBuffer("data: " + "y".repeat(200) + "\n\n", StandardCharsets.UTF_8));
                        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
                        exchange.respond(res);
                    } else {
                        respond(exchange, HttpResponseStatus.OK, "ok");
                    }
                }).build());

        client = AiClient.builder("http://127.0.0.1:" + httpServer.port())
                .maxResponseBytes(64)
                .maxStreamEventChars(64)
                .header("Accept", "application/vnd.game+json")
                .build();

        // Default Accept replaces built-in Accept instead of sending duplicate headers
        assertEquals("ok", callSync("/ok", "{}", String.class).data());
        assertEquals(List.of("application/vnd.game+json"), seenAcceptHeaders.get());

        // Oversized unary response fails with AI_PROTOCOL_ERROR
        AiResult<String> tooBig = callSync("/huge", "{}", String.class);
        assertEquals(AI_PROTOCOL_ERROR, tooBig.code());

        // Oversized SSE event aborts with AI_PROTOCOL_ERROR
        CompletableFuture<AiResult<Void>> sseTooBig = new CompletableFuture<>();
        client.stream("/huge-sse", "{}", new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) {}
            @Override public void onComplete(AiResult<Void> result) { sseTooBig.complete(result); }
        });
        assertEquals(AI_PROTOCOL_ERROR, sseTooBig.get(5, TimeUnit.SECONDS).code());
    }

    private <T> AiResult<T> callSync(String path, Object body, Class<T> type) throws Exception {
        CompletableFuture<AiResult<T>> f = new CompletableFuture<>();
        client.call(path, body, type, f::complete);
        return f.get(5, TimeUnit.SECONDS);
    }

    private static void respond(cn.managame.network.http.HttpExchange exchange, HttpResponseStatus status, String body) {
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        exchange.respond(res);
    }

    private int startSseServer(CountDownLatch sendSecond, CountDownLatch holdForever) {
        sseGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(sseGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536),
                                new SimpleChannelInboundHandler<FullHttpRequest>() {
                                    @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                        if ("/rate-limit".equals(req.uri())) {
                                            FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                    HttpResponseStatus.TOO_MANY_REQUESTS,
                                                    Unpooled.copiedBuffer("slow down", StandardCharsets.UTF_8));
                                            res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
                                            ctx.writeAndFlush(res);
                                            return;
                                        }
                                        DefaultHttpResponse headers = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                        headers.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
                                        headers.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                                        ctx.write(headers);
                                        ctx.writeAndFlush(new DefaultHttpContent(
                                                Unpooled.copiedBuffer("id: 1\nevent: token\ndata: 你\n\n", StandardCharsets.UTF_8)));
                                        Thread.ofVirtual().start(() -> {
                                            try {
                                                if ("/stream".equals(req.uri())) {
                                                    sendSecond.await(5, TimeUnit.SECONDS);
                                                    ctx.channel().eventLoop().execute(() -> {
                                                        ctx.write(new DefaultHttpContent(
                                                                Unpooled.copiedBuffer("id: 2\nevent: token\ndata: 好\n\n", StandardCharsets.UTF_8)));
                                                        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                                                    });
                                                } else if ("/trickle".equals(req.uri())) {
                                                    for (int i = 2; i <= 4; i++) {
                                                        Thread.sleep(90);
                                                        int idx = i;
                                                        ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(
                                                                new DefaultHttpContent(Unpooled.copiedBuffer(
                                                                        "id: " + idx + "\nevent: token\ndata: c" + idx + "\n\n",
                                                                        StandardCharsets.UTF_8))));
                                                    }
                                                    ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT));
                                                } else {
                                                    holdForever.await(5, TimeUnit.SECONDS);
                                                    ctx.close();
                                                }
                                            } catch (InterruptedException ignored) {}
                                        });
                                    }
                                });
                    }
                });
        sseChannel = bootstrap.bind("127.0.0.1", 0).syncUninterruptibly().channel();
        return ((InetSocketAddress) sseChannel.localAddress()).getPort();
    }

    static final class RecordingMetrics implements Metrics {
        private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

        @Override public Counter counter(String name, String... tags) {
            AtomicInteger c = counters.computeIfAbsent(key(name, tags), k -> new AtomicInteger());
            return amount -> c.addAndGet((int) amount);
        }

        @Override public Meter counter(String name, LongSupplier total, String... tags) { return () -> {}; }
        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) { return () -> {}; }

        long counterValue(String name, String tagString) {
            AtomicInteger c = counters.get(name + "|" + tagString);
            return c == null ? 0L : c.get();
        }

        private static String key(String name, String... tags) {
            StringBuilder sb = new StringBuilder(name).append('|');
            for (int i = 0; i < tags.length; i += 2) {
                if (i > 0) sb.append(',');
                sb.append(tags[i]).append('=').append(tags[i + 1]);
            }
            return sb.toString();
        }
    }
}
