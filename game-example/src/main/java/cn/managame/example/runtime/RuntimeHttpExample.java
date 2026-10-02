package cn.managame.example.runtime;

import cn.managame.network.http.HttpServer;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.http.HttpContext;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteDomain;
import com.fasterxml.jackson.core.JsonFactory;
import io.netty.handler.codec.http.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.io.StringWriter;

/** HTTP methods share Runtime Routes and may complete through a cross-Route callback. */
public final class RuntimeHttpExample {
    private RuntimeHttpExample() {}
    public static void main(String[] args) throws Exception { System.out.println(roundTrip("hello Runtime HTTP")); }

    public static String roundTrip(String message) throws Exception {
        var body = new StringWriter();
        try (var json = new JsonFactory().createGenerator(body)) {
            json.writeStartObject(); json.writeNumberField("playerId", 42); json.writeStringField("message", message); json.writeEndObject();
        }
        var methods = new Methods();
        try (var runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player"), RouteDomain.of(2, "lookup")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1, 2)))
                .httpHandlers(List.of(methods)).build();
             var server = HttpServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0))
                     .asyncHandler(runtime.http()::dispatch).build();
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            methods.runtime = runtime;
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            String base = "http://127.0.0.1:" + port;
            var echo = client.send(HttpRequest.newBuilder(URI.create(base + "/echo"))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var lookup = client.send(HttpRequest.newBuilder(URI.create(base + "/lookup?lookupId=42"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (echo.statusCode() != 200 || !echo.body().equals("{\"playerId\":42,\"received\":" + quoted(body.toString()) + "}")
                    || lookup.statusCode() != 200 || !lookup.body().equals("{\"playerId\":42,\"name\":\"player 42\"}"))
                throw new IllegalStateException("Runtime HTTP example failed");
            return message;
        }
    }
    private static String quoted(String value) throws Exception {
        var output = new StringWriter();
        try (var json = new JsonFactory().createGenerator(output)) { json.writeString(value); }
        return output.toString();
    }

    public record EchoResult(long playerId, String received) {}
    public record PlayerResult(long playerId, String name) {}

    @HttpHandler(domain = 1, routeKey = "playerId")
    public static final class Methods {
        private GameRuntime runtime;

        @HttpMethod(value = "/echo", method = "POST")
        public EchoResult echo(HttpContext context, FullHttpRequest request) {
            if (Contexts.current() != context) throw new IllegalStateException("Missing HTTP context");
            return new EchoResult(context.routeKey(), request.content().toString(StandardCharsets.UTF_8));
        }

        @HttpMethod(value = "/lookup", routeKey = "lookupId")
        public void lookup(HttpContext context) {
            long id = context.routeKey();
            runtime.call(2, id, () -> "player " + id, new RouteCallback<String>() {
                public void onSuccess(String value) {
                    if (Contexts.current() != context) throw new IllegalStateException("Source context not restored");
                    // The callback can respond later; it must not access the borrowed HTTP request here.
                    context.responseCallback().onResponse(new PlayerResult(id, value));
                }
                public void onFail(int code) {
                    context.responseCallback().onFail(new IllegalStateException("Route call failed: " + code));
                }
            });
        }
    }
}
