package cn.managame.runtime.http;

import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.FrameworkErrorCodes;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.TaskInterceptor;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.executor.RouteExecutors;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpRoutesTest {
    private static final int PLAYER = 1, GUILD = 2, WORLD = 3;
    private static final byte ROLE = 1;
    private static final int SOLD_OUT = 10_001;   // the game's own code
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    record Notice(long guildId, String text) {}
    record Posted(long guild, String text) {}
    record PayAck(String result) {}

    private final GameRuntime runtime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads(), (source, message) -> {
                if (source.businessId() == 0) throw new IllegalArgumentException("Which role?");
                return source.businessId();
            })
            .domain(GUILD, RouteExecutors.virtualThreads())
            .domain(WORLD, RouteExecutors.striped(1), RouteKeys.constant(0)).build();
    private NetworkServer server;

    @Domain(PLAYER)
    class PlayerAdmin {
        @HttpMethod("/mail") String mail(String text) {
            Context context = Context.current();
            return "mail " + text + " to " + context.routeKey() + " on domain " + context.domain();
        }
        @HttpMethod("/rank") void rank(String body) {
            HttpRequestContext context = HttpRequestContext.current();
            // As with a call to another server: the answer comes from a callback after the endpoint returned.
            Thread.ofVirtual().start(() -> runtime.execute(context, later -> later.reply("rank of " + context.businessId())));
        }
    }

    @Domain(WORLD)
    static class WorldAdmin {
        @HttpMethod(value = "/status", method = "GET") String status(FullHttpRequest request) {
            return "up for " + HttpRequests.query(request, "who");
        }
        @HttpMethod("/size") String size(byte[] body) { return body.length + " bytes"; }
        @HttpMethod("/trace") String trace(HttpRequestContext context, String body) {
            return context.request().headers().get("X-Trace") + " " + body;
        }
        @HttpMethod("/boom") String boom(String body) { throw new IllegalStateException("boom"); }
        @HttpMethod("/sold-out") void soldOut(HttpRequestContext context, String item) { context.fail(SOLD_OUT, item); }
    }

    /** The role a request acts for comes from its query, as the application decides. */
    private static Context source(FullHttpRequest request) {
        String role = HttpRequests.query(request, "roleId");
        return role == null ? GameContext.of(0, 0) : GameContext.of(0, 0, ROLE, Long.parseLong(role));
    }

    @AfterEach void stop() {
        if (server != null) server.close();
        runtime.close();
    }

    private void serve(HttpRoutes routes) {
        server = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().contextPath("/admin").handler(routes).build());
    }

    private HttpRoutes.Builder routes() {
        return HttpRoutes.builder(runtime).source(HttpRoutesTest::source).register(List.of(new PlayerAdmin(), new WorldAdmin()));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return send(request(method, path, body));
    }

    private HttpRequest.Builder request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/admin" + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A 200 of JSON, whatever the endpoint answered, as a codec for an API of its own writes it. */
    private static FullHttpResponse json(String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return response;
    }

    @Test void endpointsArePostAndTakeTheBody() throws Exception {
        serve(routes().build());
        HttpResponse<String> mail = send("POST", "/mail?roleId=1001", "hi");
        assertEquals(200, mail.statusCode());
        assertEquals("mail hi to 1001 on domain " + PLAYER, mail.body());
        assertEquals("3 bytes", send("POST", "/size", "abc").body());
        assertEquals("up for gm", send("GET", "/status?who=gm", null).body(), "a GET endpoint reads the request");
        assertEquals("t1 x", send(request("POST", "/trace", "x").header("X-Trace", "t1")).body(), "the context gives the request");
    }

    @Test void unknownPathsMethodsAndInputsAreRejected() throws Exception {
        serve(routes().build());
        assertEquals(404, send("POST", "/nobody", "x").statusCode());
        HttpResponse<String> wrongMethod = send("GET", "/mail?roleId=1", null);
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST", wrongMethod.headers().firstValue("allow").orElseThrow());
        assertEquals(400, send("POST", "/mail", "x").statusCode(), "no role to route by");
        assertEquals(400, send("POST", "/mail?roleId=abc", "x").statusCode(), "not a number");
    }

    @Test void aFailingEndpointIsAnswered500() throws Exception {
        serve(routes().build());
        HttpResponse<String> response = send("POST", "/boom", "x");
        assertEquals(500, response.statusCode());
        assertEquals(FrameworkErrorCodes.describe(RUNTIME_EXECUTION_ERROR), response.body());
    }

    @Test void anEndpointMayAnswerLaterThroughTheCurrentContext() throws Exception {
        serve(routes().build());
        assertEquals("rank of 7", send("POST", "/rank?roleId=7", "").body());
    }

    @Test void requestsTheRuntimeRefusesAreAnswered503() throws Exception {
        serve(routes().build());
        runtime.shutdown();
        assertEquals(503, send("POST", "/mail?roleId=1", "x").statusCode());
    }

    @Test void aCodecReadsArgumentsThatRouteAndShapesResults() throws Exception {
        @Domain(GUILD) class GuildAdmin {
            @HttpMethod(value = "/notice", routeKey = "guildId") Posted notice(Notice notice) { return new Posted(Context.current().routeKey(), notice.text()); }
        }
        HttpCodec json = new HttpCodec() { // a stand-in for a JSON library: "guildId:text" in, JSON out
            @Override public Object decode(FullHttpRequest request, Type type) throws Exception {
                if (type != Notice.class) return HttpCodec.basic().decode(request, type);
                String[] fields = HttpRequests.text(request).split(":", 2);
                return new Notice(Long.parseLong(fields[0]), fields[1]);
            }
            @Override public FullHttpResponse encode(Object result) throws Exception {
                if (!(result instanceof Posted posted)) return HttpCodec.basic().encode(result);
                return json("{\"guild\":" + posted.guild() + ",\"text\":\"" + posted.text() + "\"}");
            }
            @Override public FullHttpResponse encodeFailure(int errorCode, String[] args) {
                return json("{\"error\":" + errorCode + ",\"args\":\"" + String.join(",", args) + "\"}");
            }
        };
        serve(HttpRoutes.builder(runtime).codec(json).register(List.of(new GuildAdmin(), new WorldAdmin())).build());
        HttpResponse<String> posted = send("POST", "/notice", "7:hello");
        assertEquals("{\"guild\":7,\"text\":\"hello\"}", posted.body(), "the guild domain routes by the notice's guildId");
        assertEquals("application/json", posted.headers().firstValue("content-type").orElseThrow());
        assertEquals(400, send("POST", "/notice", "not a notice").statusCode());
        assertEquals("{\"error\":" + RUNTIME_EXECUTION_ERROR + ",\"args\":\"\"}", send("POST", "/boom", "x").body());
        assertEquals("{\"error\":" + SOLD_OUT + ",\"args\":\"sword\"}", send("POST", "/sold-out", "sword").body(),
                "a failure's args reach the codec");
    }

    @Test void aResultGoesThroughTheBasicCodecAsItsDataOrItsFailure() throws Exception {
        @Domain(WORLD) class Results {
            @HttpMethod("/ok") HttpResult ok(String body) { return HttpResult.ok("got " + body); }
            @HttpMethod("/empty") HttpResult empty(String body) { return HttpResult.ok(null); }
            @HttpMethod("/sold-out") HttpResult soldOut(String body) { return HttpResult.fail(SOLD_OUT, "sold out"); }
            @HttpMethod("/closing") HttpResult closing(String body) { return HttpResult.fail(RUNTIME_CLOSED, "closing"); }
        }
        serve(HttpRoutes.builder(runtime).register(List.of(new Results())).build());
        HttpResponse<String> ok = send("POST", "/ok", "x");
        assertEquals(200, ok.statusCode());
        assertEquals("got x", ok.body());
        HttpResponse<String> empty = send("POST", "/empty", "x");
        assertEquals(200, empty.statusCode());
        assertEquals("", empty.body());
        HttpResponse<String> soldOut = send("POST", "/sold-out", "x");
        assertEquals(500, soldOut.statusCode());
        assertEquals("sold out", soldOut.body(), "its msg is the text");
        assertEquals(503, send("POST", "/closing", "x").statusCode());
    }

    @Test void endpointsAnswerInTheApisShapeOrTheOtherSidesAndFailAlike() throws Exception {
        @Domain(WORLD) class Api {
            @HttpMethod("/mail") HttpResult mail(String text) { return HttpResult.ok(text); }
            @HttpMethod("/pay/notify") PayAck payNotify(String order) { return new PayAck(order + " paid"); }
            @HttpMethod("/buy") void buy(HttpRequestContext context, String item) { context.fail(SOLD_OUT, item); }
        }
        HttpCodec json = new HttpCodec() {   // a stand-in for a JSON library and the game's messages
            @Override public Object decode(FullHttpRequest request, Type type) throws Exception { return HttpCodec.basic().decode(request, type); }
            @Override public FullHttpResponse encode(Object result) {
                return json(switch (result) {
                    case HttpResult answer -> "{\"code\":" + answer.code() + ",\"data\":\"" + answer.data() + "\",\"msg\":\"" + answer.msg() + "\"}";
                    case PayAck ack -> "{\"result\":\"" + ack.result() + "\"}";
                    default -> throw new IllegalArgumentException("not JSON");
                });
            }
            @Override public FullHttpResponse encodeFailure(int errorCode, String[] args) {
                return encode(HttpResult.fail(errorCode, String.join(",", args) + " is sold out"));
            }
        };
        serve(HttpRoutes.builder(runtime).codec(json).register(List.of(new Api())).build());
        assertEquals("{\"code\":0,\"data\":\"hi\",\"msg\":\"\"}", send("POST", "/mail", "hi").body());
        assertEquals("{\"result\":\"7 paid\"}", send("POST", "/pay/notify", "7").body(), "the endpoint's own type");
        assertEquals("{\"code\":" + SOLD_OUT + ",\"data\":\"null\",\"msg\":\"sword is sold out\"}", send("POST", "/buy", "sword").body(),
                "a failure takes the codec's shape whatever the endpoint returns, with its text from the args");
    }

    @Test void aFailureTheCodecCannotEncodeIsAnswered500() throws Exception {
        HttpCodec broken = new HttpCodec() {
            @Override public Object decode(FullHttpRequest request, Type type) throws Exception { return HttpCodec.basic().decode(request, type); }
            @Override public FullHttpResponse encode(Object result) throws Exception { return HttpCodec.basic().encode(result); }
            @Override public FullHttpResponse encodeFailure(int errorCode, String[] args) throws IOException { throw new IOException("broken"); }
        };
        serve(HttpRoutes.builder(runtime).codec(broken).register(List.of(new WorldAdmin())).build());
        HttpResponse<String> response = send("POST", "/boom", "x");
        assertEquals(500, response.statusCode());
        assertEquals("", response.body());
    }

    @Test void onlyAVoidEndpointAnswersLater() throws Exception {
        @Domain(WORLD) class Forgetful { @HttpMethod("/forgets") String forgets(String body) { return null; } }
        serve(HttpRoutes.builder(runtime).register(List.of(new Forgetful())).build());
        assertEquals(500, send("POST", "/forgets", "x").statusCode());
    }

    @Test void replyingWithNothingFromAnotherThreadIsAnEmptySuccess() throws Exception {
        @Domain(WORLD) class Later {
            @HttpMethod("/nothing") void nothing(HttpRequestContext context, String body) { Thread.ofVirtual().start(() -> context.reply(null)); }
        }
        serve(HttpRoutes.builder(runtime).register(List.of(new Later())).build());
        HttpResponse<String> response = send("POST", "/nothing", "x");
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
    }

    /** Waits for what holds a request to let it go: the exchange when it is handled, the task after it ends. */
    private static void assertReleased(FullHttpRequest request) throws InterruptedException {
        for (int waited = 0; waited < 5000 && request.refCnt() > 0; waited += 10) Thread.sleep(10);
        assertEquals(0, request.refCnt());
    }

    @Test void aRequestIsReleasedHoweverItsTaskEnds() throws Exception {
        TaskInterceptor gate = new TaskInterceptor() {   // told by the query what to do with the request
            @Override public boolean before(Context context) {
                if (!(context instanceof HttpRequestContext request)) return true;
                String stop = HttpRequests.query(request.request(), "stop");
                if ("throw".equals(stop)) throw new IllegalStateException("broken");
                if ("refuse".equals(stop)) request.fail(SOLD_OUT);
                return stop == null;
            }
        };
        AtomicReference<FullHttpRequest> seen = new AtomicReference<>();
        try (GameRuntime gated = GameRuntime.builder().domain(WORLD, RouteExecutors.striped(1), RouteKeys.constant(0))
                .intercept(List.of(gate)).errorHandler((context, cause) -> {}).build()) {
            serve(HttpRoutes.builder(gated).source(request -> {
                seen.set(request);
                return GameContext.of(0, 0);
            }).register(List.of(new WorldAdmin())).build());
            assertEquals(200, send("POST", "/size", "abc").statusCode());
            assertReleased(seen.get());
            assertEquals(500, send("POST", "/boom", "x").statusCode());
            assertReleased(seen.get());
            assertEquals(500, send("POST", "/size?stop=refuse", "abc").statusCode());
            assertReleased(seen.get());
            assertEquals(500, send("POST", "/size?stop=throw", "abc").statusCode());
            assertReleased(seen.get());
            gated.shutdown();
            assertEquals(503, send("POST", "/size", "abc").statusCode());
            assertReleased(seen.get());
        }
    }

    @Test void aRequestTakenOrResponseGivenAsIsSkipsTheCodec() throws Exception {
        @Domain(WORLD) class Raw {
            @HttpMethod(value = "/raw", method = "GET") FullHttpResponse raw(FullHttpRequest request) {
                return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CREATED,
                        Unpooled.copiedBuffer("for " + HttpRequests.query(request, "who"), StandardCharsets.UTF_8));
            }
        }
        HttpCodec jsonOnly = new HttpCodec() {   // knows its own objects only
            @Override public Object decode(FullHttpRequest request, Type type) { throw new IllegalArgumentException("not JSON"); }
            @Override public FullHttpResponse encode(Object result) { throw new IllegalArgumentException("not JSON"); }
        };
        serve(HttpRoutes.builder(runtime).codec(jsonOnly).register(List.of(new Raw())).build());
        HttpResponse<String> response = send("GET", "/raw?who=gm", null);
        assertEquals(201, response.statusCode());
        assertEquals("for gm", response.body());
    }

    @Domain(WORLD) static class Duplicate { @HttpMethod(value = "/boom", method = "post") String again(String body) { return ""; } }
    @Domain(WORLD) static class NoSlash { @HttpMethod("boom") String boom(String body) { return ""; } }
    @Domain(WORLD) static class WrongContext { @HttpMethod("/x") String x(HandleContext context, String body) { return ""; } }
    @Domain(GUILD) static class Unreadable { @HttpMethod(value = "/x", routeKey = "guildId") String x(Notice notice) { return ""; } }

    @Test void registrationRejectsWhatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(List.of(new WorldAdmin(), new Duplicate())));
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(List.of(new NoSlash())));
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(List.of(new WrongContext())));
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(List.of(new Unreadable())).build(),
                "the basic codec cannot read a Notice");
    }
}
