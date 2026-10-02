package cn.managame.runtime.http;

import cn.managame.runtime.error.RuntimeError;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.http.RuntimeHttpTest.*;

class HttpResultTest {
    public record PlayerResult(long id, String name, List<String> tags) {}
    public static final class Broken {
        final AtomicInteger calls;
        Broken(AtomicInteger calls) { this.calls = calls; }
        public String getValue() { calls.incrementAndGet(); throw new IllegalStateException("serializer failure"); }
    }
    @HttpHandler(domain = 1, routeKey = "id")
    static class Methods {
        HttpResultCallback deferred;
        final AtomicInteger serializationCalls = new AtomicInteger();
        @HttpMethod(value = "/dto", method = HttpRequestMethod.GET) public PlayerResult dto() { return new PlayerResult(42, "玩家", List.of("a", "b")); }
        @HttpMethod(value = "/null", method = HttpRequestMethod.GET) public Object empty() { return null; }
        @HttpMethod(value = "/void", method = HttpRequestMethod.GET) public void deferred(HttpContext context) { deferred = context.responseCallback(); }
        @HttpMethod(value = "/bad", method = HttpRequestMethod.GET) public Broken bad() { return new Broken(serializationCalls); }
        @HttpMethod(value = "/first", method = HttpRequestMethod.GET) public Broken first(HttpContext context) {
            assertTrue(context.responseCallback().onResponse(201, Map.of("ok", true)));
            return new Broken(serializationCalls); // Losing results must not be serialized.
        }
    }
    @Test void businessDtoAndNullAreEncodedAsJsonWithoutTransportReturnTypes() {
        var executor = new QueueExecutor(); var methods = new Methods();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(methods)).build()) {
            var dto = dispatch(runtime, "GET", "/dto?id=42"); executor.next();
            assertEquals(200, dto.status); assertEquals("application/json; charset=UTF-8", dto.contentType);
            assertEquals("{\"id\":42,\"name\":\"玩家\",\"tags\":[\"a\",\"b\"]}", dto.rawBody);
            var empty = dispatch(runtime, "GET", "/null?id=42"); executor.next(); assertEquals("null", empty.rawBody);
            var first = dispatch(runtime, "GET", "/first?id=42"); executor.next();
            assertEquals(201, first.status); assertEquals("{\"ok\":true}", first.rawBody); assertEquals(1, first.calls);
            assertEquals(0, methods.serializationCalls.get());
        }
    }

    @Test void serializationFailureReportsOnceCompletesFailureAndReleasesRequest() {
        var executor = new QueueExecutor(); var methods = new Methods(); List<RuntimeError> errors = new ArrayList<>();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(methods)).errorHandler(errors::add).build()) {
            var request = request("GET", "/bad?id=42"); var result = new Result();
            runtime.http().dispatch(request, result); request.release(); executor.next();
            assertEquals(0, request.refCnt()); assertEquals(1, result.calls); assertNotNull(result.failure);
            assertEquals(1, errors.size()); assertEquals(42, errors.getFirst().routeKey()); assertEquals(1, methods.serializationCalls.get());
            var deferred = dispatch(runtime, "GET", "/void?id=42"); executor.next();
            assertTrue(methods.deferred.onResponse(new Broken(methods.serializationCalls)));
            assertNotNull(deferred.failure); assertEquals(2, errors.size()); assertEquals(1, deferred.calls);
            assertFalse(methods.deferred.onFail(new IllegalStateException("late")));
        }
    }

    @Test void deferredCompletionRejectsInvalidArgumentsWithoutClaimingAndRacesOneWinner() throws Exception {
        var executor = new QueueExecutor(); var methods = new Methods();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(methods)).build();
             var workers = Executors.newFixedThreadPool(2)) {
            var request = request("GET", "/void?id=42"); var result = new Result();
            runtime.http().dispatch(request, result); request.release(); executor.next(); assertEquals(0, request.refCnt());
            assertEquals(0, result.calls);
            assertThrows(IllegalArgumentException.class, () -> methods.deferred.onResponse(100, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> methods.deferred.onResponse(600, Map.of()));
            assertThrows(NullPointerException.class, () -> methods.deferred.onFail(null));
            var start = new CountDownLatch(1);
            var response = workers.submit(() -> { start.await(); return methods.deferred.onResponse(new PlayerResult(42, "winner", List.of())); });
            var failure = workers.submit(() -> { start.await(); return methods.deferred.onFail(new IllegalStateException("winner")); });
            start.countDown(); assertNotEquals(response.get(5, TimeUnit.SECONDS), failure.get(5, TimeUnit.SECONDS));
            result.await(); assertEquals(1, result.calls); assertFalse(methods.deferred.onResponse(null));
        }
    }

    @Test void customCodecFreezesMediaTypeAndSkipsDuplicateEncoding() {
        var executor = new QueueExecutor(); var methods = new Methods(); var calls = new AtomicInteger();
        HttpResultCodec codec = new HttpResultCodec() {
            public byte[] encode(Object result) { calls.incrementAndGet(); return ("encoded:" + result).getBytes(StandardCharsets.UTF_8); }
            public String contentType() { return "text/plain; charset=UTF-8"; }
        };
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(methods)).httpResultCodec(codec).build()) {
            // Use a raw transport capture: the custom body deliberately is not JSON.
            var body = new AtomicReference<String>(); var media = new AtomicReference<String>();
            var callback = new cn.managame.network.http.HttpResponseCallback() {
                public boolean onResponse(FullHttpResponse response) {
                    body.set(response.content().toString(StandardCharsets.UTF_8)); media.set(response.headers().get("Content-Type")); response.release(); return true;
                }
                public boolean onFail(Throwable cause) { throw new AssertionError(cause); }
            };
            var request = request("GET", "/void?id=42"); runtime.http().dispatch(request, callback); request.release(); executor.next();
            assertTrue(methods.deferred.onResponse("payload")); assertFalse(methods.deferred.onResponse("ignored"));
            assertEquals("encoded:payload", body.get()); assertEquals("text/plain; charset=UTF-8", media.get()); assertEquals(1, calls.get());
        }
        HttpResultCodec invalid = new HttpResultCodec() {
            public byte[] encode(Object result) { return new byte[0]; }
            public String contentType() { return "bad\r\nheader"; }
        };
        assertThrows(IllegalArgumentException.class, () -> rawBuilder(executor).httpResultCodec(invalid).build());
    }

    @HttpHandler(domain = 1, routeKey = "id") static class RawResponse { @HttpMethod(value = "/raw", method = HttpRequestMethod.GET) public FullHttpResponse raw() { return null; } }
    @HttpHandler(domain = 1, routeKey = "id") static class Primitive { @HttpMethod(value = "/raw", method = HttpRequestMethod.GET) public long raw() { return 1; } }
    @Test void transportAndPrimitiveReturnDeclarationsFailBuildAndDynamicTransportObjectsRemainCallerOwned() {
        var executor = new QueueExecutor();
        for (Object handler : List.of(new RawResponse(), new Primitive()))
            assertThrows(IllegalArgumentException.class, () -> rawBuilder(executor).httpHandlers(List.of(handler)).build());
        var methods = new Methods(); List<RuntimeError> errors = new ArrayList<>();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(methods)).errorHandler(errors::add).build()) {
            var result = dispatch(runtime, "GET", "/void?id=42"); executor.next();
            var transport = Unpooled.copiedBuffer("unsupported", StandardCharsets.UTF_8);
            try {
                assertTrue(methods.deferred.onResponse(transport)); assertNotNull(result.failure);
                assertEquals(1, transport.refCnt()); assertEquals(1, errors.size());
            } finally { transport.release(); }
        }
    }
}
