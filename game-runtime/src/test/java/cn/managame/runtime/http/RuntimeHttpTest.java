package cn.managame.runtime.http;

import cn.managame.network.http.HttpResponseCallback;
import cn.managame.network.http.HttpServer;
import cn.managame.runtime.*;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.event.*;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeHttpTest {
    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        System.setProperty("io.netty.leakDetection.level", "paranoid");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/http-tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent()); Files.writeString(marker, "test-only selector pipe");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    static final class QueueExecutor implements RouteExecutor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        RouteExecuteStatus status = RouteExecuteStatus.ACCEPTED;
        int closes;
        public RouteExecuteStatus tryExecute(int domain, long key, Runnable action) {
            if (status == RouteExecuteStatus.ACCEPTED) tasks.add(action);
            return status;
        }
        public void close() { closes++; }
        void next() { Objects.requireNonNull(tasks.poll()).run(); }
    }

    static final class Result implements HttpResponseCallback {
        final CountDownLatch done = new CountDownLatch(1);
        int status, calls;
        String body, rawBody, allow, contentType;
        Throwable failure;
        public boolean onResponse(FullHttpResponse response) {
            calls++; status = response.status().code(); rawBody = response.content().toString(StandardCharsets.UTF_8);
            body = rawBody;
            if (!rawBody.isEmpty()) {
                try { var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody); if (tree.isTextual()) body = tree.asText(); }
                catch (java.io.IOException cause) { throw new AssertionError(cause); }
            }
            allow = response.headers().get("Allow"); contentType = response.headers().get("Content-Type"); response.release(); done.countDown(); return true;
        }
        public boolean onFail(Throwable cause) { calls++; failure = cause; done.countDown(); return true; }
        void await() throws Exception { assertTrue(done.await(5, TimeUnit.SECONDS)); }
    }

    static GameRuntimeBuilder rawBuilder(RouteExecutor executor) {
        return GameRuntimeBuilder.builder().routeDomains(List.of(RouteDomain.of(1, "player"), RouteDomain.of(2, "guild")))
                .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1, 2)));
    }
    static GameRuntimeBuilder builder(RouteExecutor executor) {
        return rawBuilder(executor).httpContextFactory((domain, selectedKey, request, callback) -> new DefaultHttpContext(domain, 42, request, callback));
    }
    static FullHttpRequest request(String method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.valueOf(method), uri,
                Unpooled.copiedBuffer("input", StandardCharsets.UTF_8));
    }
    static String response(String text) {
        return text;
    }
    static Result dispatch(GameRuntime runtime, String method, String uri) {
        var request = request(method, uri); var result = new Result();
        try { runtime.http().dispatch(request, result); } finally { request.release(); }
        return result;
    }

    @HttpHandler(domain = 1) static class Methods {
        @HttpMethod(value = "/echo", method = HttpRequestMethod.GET) public String echo(FullHttpRequest request, HttpContext context) {
            assertSame(context, Contexts.current()); assertSame(request, context.request());
            return request.content().toString(StandardCharsets.UTF_8);
        }
        @HttpMethod(value = "/echo", method = HttpRequestMethod.POST) public void post(HttpContext context) {
            context.responseCallback().onResponse(response("post"));
        }
        @HttpMethod(value = "/domain", method = HttpRequestMethod.GET, domain = 2) public String domain(Context context) { return response(Integer.toString(context.routeDomain())); }
        @HttpMethod(value = "/encoded%2Fpath", method = HttpRequestMethod.GET) public String encoded() { return response("encoded"); }
    }

    record Input(long id, String text, List<String> tags) {}
    @HttpHandler(domain = 1, routeKey = "id") static class BoundInputs {
        @HttpMethod("/dto") public String dto(Input request) {
            assertEquals(request.id(), Contexts.current(HttpContext.class).routeKey());
            return request.text() + ":" + request.tags().size();
        }
        @HttpMethod(value = "/dto", method = HttpRequestMethod.GET) public String query(Input request) { return dto(request); }
        @HttpMethod("/string") public String string(String body) { return body; }
        @HttpMethod(value = "/generic", routeKeyMethod = "fixedKey")
        public Integer generic(Map<String, List<Input>> request) { return request.get("items").getFirst().tags().size(); }
        public long fixedKey(FullHttpRequest request) { return 99; }
    }

    static Result dispatchBody(GameRuntime runtime, String method, String uri, String body) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.valueOf(method), uri,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        var result = new Result();
        try { runtime.http().dispatch(request, result); } finally { request.release(); }
        return result;
    }

    @Test void dtoStringGenericAndRepeatedQueryValuesBindWithoutContextParameter() {
        var executor = new QueueExecutor();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new BoundInputs())).build()) {
            String body = "{\"id\":99,\"text\":\"你好\",\"tags\":[\"a\",\"b\"]}";
            var dto = dispatchBody(runtime, "POST", "/dto", body); executor.next(); assertEquals("你好:2", dto.body);
            var string = dispatchBody(runtime, "POST", "/string", body); executor.next(); assertEquals(body, string.body);
            var query = dispatchBody(runtime, "GET", "/dto?id=99&text=hello&tags=a&tags=b", "");
            executor.next(); assertEquals("hello:2", query.body);
            var generic = dispatchBody(runtime, "POST", "/generic", "{\"items\":[" + body + "]}");
            executor.next(); assertEquals("2", generic.body);
            assertEquals(400, dispatchBody(runtime, "POST", "/generic", "{\"items\":123}").status);
            assertTrue(executor.tasks.isEmpty());
            for (String invalid : List.of("{\"id\":99,\"tags\":123}", body + " null", "{\"id\":99,\"text\":[]}")) {
                assertEquals(400, dispatchBody(runtime, "POST", "/dto", invalid).status);
                assertTrue(executor.tasks.isEmpty());
            }
        }
    }

    @Test void admittedHandlerCanSubmitHttpContinuationAfterShutdownButExternalHttpRejects() throws Exception {
        record Trigger(Runnable action) {}
        @Handler(domain = 1) class Owner {
            @HandlerMethod public void handle(Trigger request) { request.action().run(); }
        }
        var executor = new QueueExecutor(); var responses = new ArrayList<Result>();
        try (var runtime = rawBuilder(executor).protocols(List.of(r -> r.register(Protocols.request(1, Trigger.class))))
                .handlers(List.of(new Owner())).httpHandlers(List.of(new BoundInputs())).build()) {
            runtime.dispatch(null, 7L, new Trigger(() -> responses.add(dispatchBody(runtime, "POST", "/string", "{\"id\":99}"))));
            runtime.shutdown();
            assertEquals(503, dispatchBody(runtime, "POST", "/string", "{\"id\":99}").status);
            executor.next(); assertFalse(runtime.awaitTermination(Duration.ZERO));
            executor.next(); assertTrue(runtime.awaitTermination(Duration.ofSeconds(1)));
            assertEquals(200, responses.getFirst().status); assertEquals("{\"id\":99}", responses.getFirst().body);
        }
    }

    @Test void exactLookupQueryExclusionMethodAllowAndDomainOverride() {
        var executor = new QueueExecutor();
        try (var runtime = builder(executor).httpHandlers(List.of(new Methods())).build()) {
            var get = dispatch(runtime, "GET", "/echo?a=1"); assertEquals(1, executor.tasks.size()); executor.next();
            assertEquals("input", get.body); assertNull(Contexts.currentOrNull());
            var post = dispatch(runtime, "POST", "/echo"); executor.next(); assertEquals("post", post.body);
            var domain = dispatch(runtime, "GET", "/domain"); executor.next(); assertEquals("2", domain.body);
            assertEquals(404, dispatch(runtime, "GET", "/echo/").status);
            var wrongMethod = dispatch(runtime, "HEAD", "/echo");
            assertEquals(405, wrongMethod.status); assertEquals("GET, POST", wrongMethod.allow);
            assertEquals(404, dispatch(runtime, "GET", "/encoded/path").status);
            var encoded = dispatch(runtime, "GET", "/encoded%2Fpath"); executor.next(); assertEquals("encoded", encoded.body);
            assertEquals(400, dispatch(runtime, "GET", "http://host/echo").status);
            assertEquals(400, dispatch(runtime, "GET", "/echo#fragment").status);
            assertTrue(executor.tasks.isEmpty());
        }
    }

    @HttpHandler(domain = 1, routeKey = "id") static class Verbs {
        @HttpMethod("/post-only") public String defaultPost(HttpContext context) { return result(context); }
        @HttpMethod("/verbs") public String post(HttpContext context) { return result(context); }
        @HttpMethod(value = "/verbs", method = HttpRequestMethod.GET) public String get(HttpContext context) { return result(context); }
        @HttpMethod(value = "/verbs", method = HttpRequestMethod.PUT) public String put(HttpContext context) { return result(context); }
        @HttpMethod(value = "/verbs", method = HttpRequestMethod.PATCH) public String patch(HttpContext context) { return result(context); }
        @HttpMethod(value = "/verbs", method = HttpRequestMethod.DELETE) public String delete(HttpContext context) { return result(context); }
        @HttpMethod(value = "/verbs", method = HttpRequestMethod.HEAD) public String head(HttpContext context) { return result(context); }
        @HttpMethod(value = "/verbs", method = HttpRequestMethod.OPTIONS) public String options(HttpContext context) { return result(context); }
        @HttpMethod(value = "/verbs", method = HttpRequestMethod.TRACE) public String trace(HttpContext context) { return result(context); }
        private String result(HttpContext context) { return context.request().method().name() + ":" + context.routeKey(); }
    }
    @Test void defaultPostAndExplicitVerbsDispatchByExactMethodAndPreserveKeySources() {
        var executor = new QueueExecutor();
        try (var runtime = rawBuilder(executor).httpHandlers(List.of(new Verbs())).build()) {
            var wrongMethod = dispatch(runtime, "GET", "/post-only?id=99");
            assertEquals(405, wrongMethod.status); assertEquals("POST", wrongMethod.allow);
            assertTrue(executor.tasks.isEmpty());
            for (String path : List.of("/post-only", "/verbs")) {
                var request = request("POST", path + "?id=99"); var result = new Result();
                request.content().clear().writeCharSequence("{\"id\":42}", StandardCharsets.UTF_8);
                runtime.http().dispatch(request, result); request.release(); executor.next();
                assertEquals("POST:42", result.body); assertEquals(0, request.refCnt());
            }
            for (HttpRequestMethod verb : HttpRequestMethod.values()) {
                var request = request(verb.name(), "/verbs?id=99"); var result = new Result();
                request.content().clear().writeCharSequence("{\"id\":42}", StandardCharsets.UTF_8);
                runtime.http().dispatch(request, result); request.release(); executor.next();
                assertEquals(verb.name() + ":" + (verb == HttpRequestMethod.GET ? 99 : 42), result.body);
                assertEquals(200, result.status); assertEquals(0, request.refCnt());
            }
            var unknown = dispatch(runtime, "PURGE", "/verbs?id=99");
            assertEquals(405, unknown.status);
            assertEquals("DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT, TRACE", unknown.allow);
            assertTrue(executor.tasks.isEmpty());
        }
    }

    @Test void acceptedRequestIsRetainedThroughExecutionAndReleasedAfterReturn() {
        var executor = new QueueExecutor();
        try (var runtime = builder(executor).httpHandlers(List.of(new Methods())).build()) {
            var request = request("GET", "/echo"); var result = new Result();
            runtime.http().dispatch(request, result);
            assertEquals(2, request.refCnt()); request.release(); assertEquals(1, request.refCnt());
            executor.next(); assertEquals("input", result.body); assertEquals(0, request.refCnt()); assertEquals(1, result.calls);
        }
    }

    @Test void rejectionAndRuntimeClosureNeverExecuteAndReleaseTemporaryOwnership() {
        for (var rejection : List.of(RouteExecuteStatus.OVERLOADED, RouteExecuteStatus.CLOSED)) {
            var executor = new QueueExecutor(); executor.status = rejection;
            try (var runtime = builder(executor).httpHandlers(List.of(new Methods())).build()) {
                var request = request("GET", "/echo"); var result = new Result();
                runtime.http().dispatch(request, result); assertEquals(1, request.refCnt()); request.release();
                assertEquals(503, result.status); assertTrue(executor.tasks.isEmpty());
                runtime.close(); assertEquals(503, dispatch(runtime, "GET", "/echo").status);
            }
        }
    }

    @Test void acceptedQueuedWorkStillRunsAfterRuntimeClose() {
        var executor = new QueueExecutor();
        try (var runtime = builder(executor).httpHandlers(List.of(new Methods())).build()) {
            var request = request("GET", "/echo"); var result = new Result();
            runtime.http().dispatch(request, result); request.release(); runtime.close();
            assertEquals(1, executor.closes); assertEquals(1, request.refCnt());
            executor.next(); assertEquals("input", result.body); assertEquals(0, request.refCnt());
        }
    }

    @Test void factoryRejectsInputWithoutBusinessDispatchAndCanSendAuthenticationResponse() {
        for (HttpContextFactory factory : List.<HttpContextFactory>of(
                (domain, selectedKey, request, callback) -> new DefaultHttpContext(domain, 0, request, callback),
                (domain, selectedKey, request, callback) -> { throw new IllegalArgumentException("invalid player key"); })) {
            var executor = new QueueExecutor();
            try (var runtime = builder(executor).httpHandlers(List.of(new Methods())).httpContextFactory(factory).build()) {
                assertEquals(400, dispatch(runtime, "GET", "/echo").status); assertTrue(executor.tasks.isEmpty());
            }
        }
        var executor = new QueueExecutor();
        try (var runtime = builder(executor).httpHandlers(List.of(new Methods())).httpContextFactory((domain, selectedKey, request, callback) -> {
            callback.onResponse(401, Map.of("error", "unauthorized")); return null;
        }).build()) {
            assertEquals(401, dispatch(runtime, "GET", "/echo").status); assertTrue(executor.tasks.isEmpty());
        }
    }

    @Test void incompatibleFactoryContextsAndUnexpectedFactoryExceptionsReportFailure() {
        for (HttpContextFactory factory : List.<HttpContextFactory>of(
                (domain, selectedKey, request, callback) -> new DefaultHttpContext(2, 42, request, callback),
                (domain, selectedKey, request, callback) -> new DefaultHttpContext(domain, 42, request, new HttpResultCallback() { public boolean onResponse(int status, Object value) { return true; } public boolean onFail(Throwable cause) { return true; } }),
                (domain, selectedKey, request, callback) -> null,
                (domain, selectedKey, request, callback) -> { throw new IllegalStateException("factory bug"); })) {
            List<RuntimeError> errors = new ArrayList<>(); var executor = new QueueExecutor();
            try (var runtime = builder(executor).httpHandlers(List.of(new Methods())).httpContextFactory(factory).errorHandler(errors::add).build()) {
                var result = dispatch(runtime, "GET", "/echo"); assertNotNull(result.failure); assertEquals(1, result.calls);
                assertEquals(1, errors.size()); assertTrue(executor.tasks.isEmpty());
            }
        }
    }

    @HttpHandler(domain = 1) static class Failures {
        @HttpMethod(value = "/throw", method = HttpRequestMethod.GET) public void broken() { throw new IllegalStateException("method bug"); }
        @HttpMethod(value = "/null", method = HttpRequestMethod.GET) public String missing() { return null; }
        @HttpMethod(value = "/completed", method = HttpRequestMethod.GET) public String completed(HttpContext context) {
            context.responseCallback().onResponse(response("first")); return response("duplicate");
        }
        @HttpMethod(value = "/late-throw", method = HttpRequestMethod.GET) public void lateThrow(HttpContext context) {
            context.responseCallback().onResponse(response("first")); throw new IllegalStateException("after completion");
        }
    }
    @Test void methodFailureNullJsonAndCompletionBeforeReturnHaveOneResponse() {
        List<RuntimeError> errors = new ArrayList<>(); var executor = new QueueExecutor();
        try (var runtime = builder(executor).httpHandlers(List.of(new Failures())).errorHandler(errors::add).build()) {
            var failed = dispatch(runtime, "GET", "/throw"); executor.next(); assertNotNull(failed.failure); assertEquals(1, failed.calls);
            var empty = dispatch(runtime, "GET", "/null"); executor.next(); assertEquals(200, empty.status); assertEquals("null", empty.rawBody);
            for (String path : List.of("/completed", "/late-throw")) {
                var result = dispatch(runtime, "GET", path); executor.next(); assertEquals("first", result.body); assertEquals(1, result.calls);
            }
            assertEquals(2, errors.size()); assertNull(Contexts.currentOrNull());
        }
    }

    static final class SessionContext extends DefaultHttpContext {
        final String session = "http-session";
        SessionContext(int domain, long key, FullHttpRequest request, HttpResultCallback callback) {
            super(domain, key, request, callback);
        }
    }
    @HttpHandler(domain = 1) static class CrossRoute {
        GameRuntime runtime;
        HttpContext original;
        boolean eventSeen;
        @HttpMethod(value = "/call", method = HttpRequestMethod.GET) public void call(SessionContext context) {
            original = context;
            assertFalse(InvocationContext.class.isInstance(context));
            runtime.eventBus().publish(new Notice(context.routeDomain(), context.routeKey(), () -> {
                var current = Contexts.current(EventContext.class);
                assertEquals(0, current.businessIdType()); assertEquals(0, current.businessId());
                assertTrue(current.metadata().isEmpty());
                eventSeen = true;
            }));
            assertTrue(eventSeen); assertSame(context, Contexts.current());
            runtime.call(2, 99, () -> {
                var current = Contexts.current(RouteCallContext.class);
                assertEquals(0, current.businessIdType()); assertEquals(0, current.businessId());
                assertTrue(current.metadata().isEmpty()); return "done";
            }, new RouteCallback<String>() {
                public void onSuccess(String text) {
                    assertSame(context, Contexts.current()); assertEquals(0, context.request().refCnt());
                    assertEquals("http-session", Contexts.current(SessionContext.class).session);
                    context.responseCallback().onResponse(response(text));
                }
                public void onFail(int code) { context.responseCallback().onFail(new IllegalStateException(Integer.toString(code))); }
            });
        }
    }
    @Test void httpEventsAndCallsUseDefaultIdentityAndDeferredCompletionRestoresHttpContext() {
        var executor = new QueueExecutor(); var handler = new CrossRoute();
        try (var runtime = builder(executor).httpHandlers(List.of(handler)).eventHandlers(List.of(new Events()))
                .httpContextFactory((domain, key, request, callback) -> new SessionContext(domain, 42, request, callback)).build()) {
            handler.runtime = runtime;
            var result = dispatch(runtime, "GET", "/call");
            executor.next(); assertEquals(0, result.calls); assertEquals(0, handler.original.request().refCnt());
            executor.next(); executor.next(); assertEquals("done", result.body); assertNull(Contexts.currentOrNull());
        }
    }

    @HttpHandler(domain = 1) static class Nested {
        GameRuntime runtime;
        HttpContext outer;
        List<String> order = new ArrayList<>();
        @HttpMethod(value = "/outer", method = HttpRequestMethod.GET) public String outer(HttpContext context) {
            outer = context; order.add("outer"); var nested = dispatch(runtime, "GET", "/inner");
            assertEquals("inner", nested.body); assertSame(context, Contexts.current()); order.add("restored"); return response("outer");
        }
        @HttpMethod(value = "/inner", method = HttpRequestMethod.GET) public String inner(HttpContext context) {
            assertNotSame(outer, Contexts.current()); order.add("inner"); return response("inner");
        }
    }
    @Test void sameRouteHttpNestingRunsInlineAndRestoresOriginalContext() {
        var executor = new QueueExecutor(); var handler = new Nested();
        try (var runtime = builder(executor).httpHandlers(List.of(handler)).build()) {
            handler.runtime = runtime; var result = dispatch(runtime, "GET", "/outer"); executor.next();
            assertEquals(List.of("outer", "inner", "restored"), handler.order); assertEquals("outer", result.body);
            assertTrue(executor.tasks.isEmpty()); assertNull(Contexts.currentOrNull());
        }
    }

    record Message(Runnable action) {}
    record Notice(int routeDomain, long routeKey, Runnable action) implements Event {}
    @Handler(domain = 1) static class Messages { @HandlerMethod public void run(Message message) { message.action().run(); } }
    static class Events { @EventMethod public void run(Notice event) { event.action().run(); } }
    @HttpHandler(domain = 1) static class Blocking {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @HttpMethod(value = "/work", method = HttpRequestMethod.GET) public String work(HttpContext context) throws Exception {
            assertSame(context, Contexts.current());
            if (context.routeKey() == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            return response("work");
        }
    }
    @Test void httpMessagesAndEventsShareSerialRouteWhileOtherKeysProgress() throws Exception {
        var handler = new Blocking(); CountDownLatch message = new CountDownLatch(1), event = new CountDownLatch(1);
        var key = new AtomicLong(1);
        try (var runtime = builder(RouteExecutors.virtualThreads()).httpHandlers(List.of(handler))
                .httpContextFactory((domain, selectedKey, request, callback) -> new DefaultHttpContext(domain, key.get(), request, callback))
                .protocols(List.of(registrar -> registrar.register(Protocols.request(1, Message.class))))
                .handlers(List.of(new Messages())).eventHandlers(List.of(new Events())).build()) {
            try {
                var first = dispatch(runtime, "GET", "/work"); assertTrue(handler.entered.await(5, TimeUnit.SECONDS));
                runtime.dispatch(new DefaultHandlerContext(1, 1, new Message(message::countDown)));
                runtime.eventBus().publish(new Notice(1, 1, event::countDown));
                key.set(2); var other = dispatch(runtime, "GET", "/work"); other.await();
                assertEquals(1, message.getCount()); assertEquals(1, event.getCount());
                handler.release.countDown(); first.await();
                assertTrue(message.await(5, TimeUnit.SECONDS)); assertTrue(event.await(5, TimeUnit.SECONDS));
            } finally { handler.release.countDown(); }
        }
    }

    @HttpHandler(domain = 1) static class PrivateMethod { @HttpMethod(value = "/bad", method = HttpRequestMethod.GET) private void bad() {} }
    @HttpHandler(domain = 1) static class StaticMethod { @HttpMethod(value = "/bad", method = HttpRequestMethod.GET) public static void bad() {} }
    @HttpHandler(domain = 1) static class WrongReturn { @HttpMethod(value = "/bad", method = HttpRequestMethod.GET) public FullHttpResponse bad() { return null; } }
    @HttpHandler(domain = 1) static class WrongParameter { @HttpMethod(value = "/bad", method = HttpRequestMethod.GET) public void bad(String first, String second) {} }
    @HttpHandler(domain = 1) static class InvocationParameter { @HttpMethod(value = "/bad", method = HttpRequestMethod.GET) public void bad(InvocationContext context) {} }
    @HttpHandler(domain = 3) static class UnknownDomain { @HttpMethod(value = "/bad", method = HttpRequestMethod.GET) public void bad() {} }
    @HttpHandler(domain = 1) static class InvalidPath { @HttpMethod(value = "/bad?x=1", method = HttpRequestMethod.GET) public void bad() {} }
    @Test void invalidRegistrationFailsBeforeResourceOwnershipTransfers() {
        var executor = new QueueExecutor();
        for (Object handler : List.of(new Object(), new PrivateMethod(), new StaticMethod(), new WrongReturn(),
                new WrongParameter(), new InvocationParameter(), new UnknownDomain(), new InvalidPath()))
            assertThrows(IllegalArgumentException.class, () -> builder(executor).httpHandlers(List.of(handler)).build());
        assertThrows(IllegalArgumentException.class, () -> rawBuilder(executor).httpHandlers(List.of(new Methods())).build());
        assertThrows(IllegalArgumentException.class, () -> builder(executor).httpHandlers(List.of(new Methods(), new Methods())).build());
        assertEquals(0, executor.closes);
    }

    @HttpHandler(domain = 1) static class Replacement { @HttpMethod(value = "/replacement", method = HttpRequestMethod.GET) public String get() { return response("replacement"); } }
    @Test void builderReplacementFreezesHttpBindingsAndEmptyRegistryNeedsNoFactory() {
        var executor = new QueueExecutor(); var builder = builder(executor).httpHandlers(List.of(new Methods()));
        try (var first = builder.build(); var second = builder.httpHandlers(List.of(new Replacement())).build();
             var empty = rawBuilder(new QueueExecutor()).build()) {
            assertEquals(404, dispatch(first, "GET", "/replacement").status);
            assertEquals(404, dispatch(second, "GET", "/echo").status);
            assertEquals(404, dispatch(empty, "GET", "/echo").status);
            var result = dispatch(first, "GET", "/echo"); executor.next(); assertEquals("input", result.body);
        }
    }

    @Test void realHttpServerUsesRuntimeRoutesAndMapsHandlerFailureTo500() throws Exception {
        List<RuntimeError> errors = new CopyOnWriteArrayList<>();
        try (var runtime = builder(RouteExecutors.virtualThreads()).httpHandlers(List.of(new Methods(), new Failures())).errorHandler(errors::add).build();
             var server = HttpServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0)).asyncHandler(runtime.http()::dispatch).build();
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            server.start(); int port = ((InetSocketAddress) server.localAddress()).getPort();
            for (String path : List.of("/echo", "/throw")) {
                var response = client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(path.equals("/echo") ? 200 : 500, response.statusCode());
            }
            assertEquals(1, errors.size());
        }
    }
}
