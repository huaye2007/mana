package cn.managame.runtime.http;

import cn.managame.runtime.context.Contexts;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.http.RuntimeHttpTest.*;

class HttpRouteKeyTest {
    @HttpHandler(domain = 1, routeKey = "playerId")
    static class Fields {
        @HttpMethod("/default") public String get(HttpContext context) {
            assertSame(context, Contexts.current());
            assertFalse(context instanceof cn.managame.runtime.context.InvocationContext);
            return response(Long.toString(context.routeKey()));
        }
        @HttpMethod(value = "/default", method = "POST") public String post(HttpContext context) {
            return response(context.routeKey() + ":" + context.request().content().toString(StandardCharsets.UTF_8));
        }
        @HttpMethod(value = "/override", routeKey = "guildId") public String override(HttpContext context) {
            return response(Long.toString(context.routeKey()));
        }
        @HttpMethod(value = "/custom", routeKeyMethod = "headerKey") public String custom(HttpContext context) {
            return response(Long.toString(context.routeKey()));
        }
        public Long headerKey(FullHttpRequest request) {
            String value = request.headers().get("X-Key");
            if (value.equals("bug")) throw new IllegalStateException("extractor bug");
            if (value.equals("null")) return null;
            return Long.valueOf(value);
        }
    }
    @HttpHandler(domain = 1, routeKeyMethod = "headerKey")
    static class MethodDefault {
        @HttpMethod("/method") public String get(HttpContext context) { return response(Long.toString(context.routeKey())); }
        @HttpMethod(value = "/field", routeKey = "id") public String field(HttpContext context) { return get(context); }
        public long headerKey(FullHttpRequest request) { return Long.parseLong(request.headers().get("X-Key")); }
    }

    static FullHttpRequest body(String method, String uri, String json) {
        var request = request(method, uri);
        request.content().clear().writeCharSequence(json, StandardCharsets.UTF_8);
        return request;
    }

    @Test void classFieldAndMethodOverrideUseGetQueryAndOtherMethodsUseJsonBody() {
        var executor = new QueueExecutor();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Fields())).build()) {
            var result = dispatch(runtime, "GET", "/default?playerId=42"); executor.next(); assertEquals("42", result.body);
            result = dispatch(runtime, "GET", "/override?playerId=42&guildId=-73"); executor.next(); assertEquals("-73", result.body);
            // GET must use query even if a body contains a valid selected field.
            var get = body("GET", "/default", "{\"playerId\":42}"); var missing = new Result();
            try { runtime.http().dispatch(get, missing); assertEquals(400, missing.status); } finally { get.release(); }
            var post = body("POST", "/default?playerId=999", "{\"playerId\":73,\"extra\":{\"id\":1}}");
            int reader = post.content().readerIndex(), writer = post.content().writerIndex(); var posted = new Result();
            runtime.http().dispatch(post, posted);
            assertEquals(reader, post.content().readerIndex()); assertEquals(writer, post.content().writerIndex());
            assertEquals(2, post.refCnt()); post.release(); executor.next();
            assertEquals("73:{\"playerId\":73,\"extra\":{\"id\":1}}", posted.body); assertEquals(0, post.refCnt());
            assertEquals(400, dispatch(runtime, "GET", "/override?playerId=42").status); // No fallback after a failed override.
            assertTrue(executor.tasks.isEmpty());
        }
    }

    @Test void decodedQueryMustContainOneExactIntegralNonzeroValue() {
        var executor = new QueueExecutor();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Fields())).build()) {
            for (String query : List.of("", "playerId=", "playerId=0", "playerId=-0", "playerId=1&playerId=2",
                    "playerId=1&%70layerId=2", "playerId=1.0", "playerId=1e2", "playerId=%2B42",
                    "playerId=9223372036854775808", "playerId=bad", "playerId=%XY", "PlayerId=42")) {
                assertEquals(400, dispatch(runtime, "GET", "/default?" + query).status, query);
                assertTrue(executor.tasks.isEmpty());
            }
            var result = dispatch(runtime, "GET", "/default?%70layerId=-9223372036854775808");
            executor.next(); assertEquals(Long.toString(Long.MIN_VALUE), result.body);
        }
    }

    @Test void jsonMustBeOneValidObjectWithAnUnambiguousTopLevelLong() {
        var executor = new QueueExecutor();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Fields())).build()) {
            for (String json : List.of("", "[]", "null", "{}", "{\"nested\":{\"playerId\":1}}",
                    "{\"playerId\":0}", "{\"playerId\":null}", "{\"playerId\":true}", "{\"playerId\":{}}",
                    "{\"playerId\":1.0}", "{\"playerId\":1e0}", "{\"playerId\":9223372036854775808}",
                    "{\"playerId\":\"+1\"}", "{\"playerId\":\" 1\"}", "{\"playerId\":1,\"playerId\":2}",
                    "{\"playerId\":1,\"x\":1,\"x\":2}", "{\"playerId\":1} {}", "{\"playerId\":1,}",
                    "{\"playerId\":1,\"x\":}", "{\"playerId\":1")) {
                var request = body("POST", "/default?playerId=42", json); var result = new Result();
                try { runtime.http().dispatch(request, result); assertEquals(400, result.status, json); assertEquals(1, request.refCnt()); }
                finally { request.release(); }
                assertTrue(executor.tasks.isEmpty());
            }
            for (String json : List.of("{\"playerId\":\"9223372036854775807\"}",
                    "{\"playerId\":-9223372036854775808}", "{\"playerId\":7,\"text\":\"玩家\"}")) {
                var request = body("POST", "/default", json); var result = new Result();
                runtime.http().dispatch(request, result); request.release(); executor.next();
                assertEquals(200, result.status); assertTrue(result.body.endsWith(json)); assertEquals(0, request.refCnt());
            }
        }
    }

    @Test void jsonExtractionUsesReadableRegionAndRejectsMalformedUtf8() {
        var executor = new QueueExecutor();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Fields())).build()) {
            var request = body("POST", "/default", "prefix{\"playerId\":4}"); request.content().readerIndex(6);
            var result = new Result(); runtime.http().dispatch(request, result);
            assertEquals(6, request.content().readerIndex()); request.release(); executor.next(); assertEquals("4:{\"playerId\":4}", result.body);
            var malformed = body("POST", "/default", "{\"playerId\":4,\"x\":\"");
            malformed.content().writeByte(0xc3).writeByte(0x28).writeCharSequence("\"}", StandardCharsets.UTF_8);
            try { var rejected = new Result(); runtime.http().dispatch(malformed, rejected); assertEquals(400, rejected.status); }
            finally { malformed.release(); }
        }
    }

    @Test void namedMethodOverridesClassFieldAndMethodFieldOverridesClassMethod() {
        var executor = new QueueExecutor(); List<cn.managame.runtime.error.RuntimeError> errors = new ArrayList<>();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Fields(), new MethodDefault())).errorHandler(errors::add).build()) {
            for (String path : List.of("/custom?playerId=0", "/method")) {
                var request = request("GET", path); request.headers().set("X-Key", "64"); var result = new Result();
                runtime.http().dispatch(request, result); request.release(); executor.next(); assertEquals("64", result.body);
            }
            var field = dispatch(runtime, "GET", "/field?id=8"); executor.next(); assertEquals("8", field.body);
            for (String value : List.of("0", "bad", "bug", "null")) {
                var request = request("GET", "/custom"); request.headers().set("X-Key", value); var result = new Result();
                try { runtime.http().dispatch(request, result); assertEquals(1, request.refCnt()); }
                finally { request.release(); }
                if (value.equals("0") || value.equals("bad")) assertEquals(400, result.status);
                else assertNotNull(result.failure);
                assertTrue(executor.tasks.isEmpty());
            }
            assertEquals(2, errors.size());
        }
    }

    @Test void selectedKeyReachesFactoryAndCannotBeReplaced() {
        var executor = new QueueExecutor(); var calls = new AtomicInteger();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Fields())).httpContextFactory((domain, key, request, callback) -> {
            calls.incrementAndGet(); assertEquals(51, key); return new DefaultHttpContext(domain, key, request, callback);
        }).build()) {
            var result = dispatch(runtime, "GET", "/default?playerId=51"); executor.next(); assertEquals("51", result.body);
            assertEquals(400, dispatch(runtime, "GET", "/default?playerId=0").status); assertEquals(1, calls.get());
        }
        List<cn.managame.runtime.error.RuntimeError> errors = new ArrayList<>();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Fields())).httpContextFactory((domain, key, request, callback) ->
                new DefaultHttpContext(domain, key + 1, request, callback)).errorHandler(errors::add).build()) {
            var result = dispatch(runtime, "GET", "/default?playerId=51"); assertNotNull(result.failure);
            assertEquals(1, errors.size()); assertTrue(executor.tasks.isEmpty());
        }
    }

    @HttpHandler(domain = 1, routeKey = "id", routeKeyMethod = "key") static class Both { @HttpMethod("/bad") public void bad() {} }
    @HttpHandler(domain = 1, routeKeyMethod = "missing") static class Missing { @HttpMethod("/bad") public void bad() {} }
    @HttpHandler(domain = 1, routeKeyMethod = "key") static class Static { @HttpMethod("/bad") public void bad() {} public static long key(FullHttpRequest r) { return 1; } }
    @HttpHandler(domain = 1, routeKeyMethod = "key") static class WrongType { @HttpMethod("/bad") public void bad() {} public int key(FullHttpRequest r) { return 1; } }
    @HttpHandler(domain = 1, routeKey = "id") static class BadMethod { @HttpMethod(value = "/bad", routeKey = " ") public void bad() {} }
    static final class CustomContext extends DefaultHttpContext {
        CustomContext(int domain, long key, FullHttpRequest request, HttpResultCallback callback) {
            super(domain, key, request, callback);
        }
    }
    @HttpHandler(domain = 1, routeKey = "id") static class Custom {
        @HttpMethod("/custom-context") public String get(CustomContext context) {
            assertSame(context, Contexts.current()); return response(Long.toString(context.routeKey()));
        }
    }
    @Test void customContextRequiresFactoryAndKeepsSelectedKeyAndOriginalType() {
        var executor = new QueueExecutor();
        assertThrows(IllegalArgumentException.class, () -> rawBuilder(executor).httpHandlers(List.of(new Custom())).build());
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Custom()))
                .httpContextFactory(CustomContext::new).build()) {
            var result = dispatch(runtime, "GET", "/custom-context?id=57"); executor.next(); assertEquals("57", result.body);
        }
    }
    @Test void invalidExtractorRegistrationRejectsBeforeOwnershipTransfer() {
        var executor = new QueueExecutor();
        for (Object handler : List.of(new Both(), new Missing(), new Static(), new WrongType(), new BadMethod()))
            assertThrows(IllegalArgumentException.class, () -> rawBuilder(executor).httpHandlers(List.of(handler)).build());
        assertEquals(0, executor.closes);
    }
}
