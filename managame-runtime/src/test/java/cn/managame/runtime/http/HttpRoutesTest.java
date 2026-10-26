package cn.managame.runtime.http;

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
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.RouteKey;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.executor.RouteExecutors;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpRoutesTest {
    private static final int PLAYER = 1, GUILD = 2, WORLD = 3;
    private static final byte ROLE = 1;
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    record Notice(@RouteKey(GUILD) long guildId, String text) {}
    record Posted(long guild, String text) {}

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
            return "mail " + text + " to " + context.routingKey() + " on domain " + context.domain();
        }
        @HttpMethod("/rank") String rank(String body) {
            HttpRequestContext context = HttpRequestContext.current();
            // As with a call to another server: the answer comes from a callback after the endpoint returned.
            Thread.ofVirtual().start(() -> runtime.execute(context, later -> later.reply("rank of " + context.businessId())));
            return null;
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
        return HttpRoutes.builder(runtime).source(HttpRoutesTest::source).register(new PlayerAdmin(), new WorldAdmin());
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
            @HttpMethod("/notice") Posted notice(Notice notice) { return new Posted(Context.current().routingKey(), notice.text()); }
        }
        HttpCodec json = new HttpCodec() { // a stand-in for a JSON library: "guildId:text" in, JSON out
            @Override public Object decode(FullHttpRequest request, Type type) throws Exception {
                if (type != Notice.class) return HttpCodec.basic().decode(request, type);
                String[] fields = HttpRequests.text(request).split(":", 2);
                return new Notice(Long.parseLong(fields[0]), fields[1]);
            }
            @Override public FullHttpResponse encode(Object result) throws Exception {
                if (!(result instanceof Posted posted)) return HttpCodec.basic().encode(result);
                return response("{\"guild\":" + posted.guild() + ",\"text\":\"" + posted.text() + "\"}");
            }
            @Override public FullHttpResponse encodeFailure(int errorCode) { return response("{\"error\":" + errorCode + "}"); }
            private FullHttpResponse response(String body) {
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                return response;
            }
        };
        serve(HttpRoutes.builder(runtime).codec(json).register(new GuildAdmin(), new WorldAdmin()).build());
        HttpResponse<String> posted = send("POST", "/notice", "7:hello");
        assertEquals("{\"guild\":7,\"text\":\"hello\"}", posted.body(), "the guild domain routes by the notice's @RouteKey");
        assertEquals("application/json", posted.headers().firstValue("content-type").orElseThrow());
        assertEquals(400, send("POST", "/notice", "not a notice").statusCode());
        assertEquals("{\"error\":" + RUNTIME_EXECUTION_ERROR + "}", send("POST", "/boom", "x").body());
    }

    @Domain(WORLD) static class Duplicate { @HttpMethod(value = "/boom", method = "post") String again(String body) { return ""; } }
    @Domain(WORLD) static class NoSlash { @HttpMethod("boom") String boom(String body) { return ""; } }
    @Domain(WORLD) static class WrongContext { @HttpMethod("/x") String x(HandleContext context, String body) { return ""; } }
    @Domain(GUILD) static class Unreadable { @HttpMethod("/x") String x(Notice notice) { return ""; } }

    @Test void registrationRejectsWhatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(new WorldAdmin(), new Duplicate()));
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(new NoSlash()));
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(new WrongContext()));
        assertThrows(IllegalArgumentException.class, () -> HttpRoutes.builder(runtime).register(new Unreadable()).build(),
                "the basic codec cannot read a Notice");
    }
}
