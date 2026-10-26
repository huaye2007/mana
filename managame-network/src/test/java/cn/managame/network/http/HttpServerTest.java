package cn.managame.network.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.TestCertificates;
import cn.managame.network.netty.NetworkServer;
import cn.managame.network.netty.NetworkSsl;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpServerTest {
    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor();

    @AfterAll static void stop() { LATER.shutdownNow(); }

    private static FullHttpResponse text(HttpResponseStatus status, String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        return response;
    }

    private static HttpResponse<String> send(NetworkServer server, String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void answersWithAggregatedRequestsAndContentLength() throws Exception {
        HttpInitializer echo = HttpInitializer.http()
                .handler(exchange -> exchange.respond(text(HttpResponseStatus.OK, exchange.request().method() + " "
                        + exchange.request().uri() + " " + exchange.request().content().toString(UTF_8))))
                .build();
        try (NetworkServer server = NetworkServer.start(LOCAL, echo)) {
            HttpResponse<String> get = send(server, "GET", "/hello?x=1", null);
            assertEquals(200, get.statusCode());
            assertEquals("GET /hello?x=1 ", get.body());
            assertEquals(String.valueOf(get.body().length()), get.headers().firstValue("content-length").orElseThrow());

            HttpResponse<String> post = send(server, "POST", "/echo", "abc");
            assertEquals("POST /echo abc", post.body());
        }
    }

    @Test void answersLaterFromAnotherThread() throws Exception {
        HttpInitializer later = HttpInitializer.http()
                .handler(exchange -> {
                    String body = exchange.request().content().toString(UTF_8); // the request is released on return
                    LATER.schedule(() -> exchange.respond(text(HttpResponseStatus.OK, "late " + body)), 50, TimeUnit.MILLISECONDS);
                })
                .build();
        try (NetworkServer server = NetworkServer.start(LOCAL, later)) {
            assertEquals("late x", send(server, "POST", "/", "x").body());
            assertEquals("late y", send(server, "POST", "/", "y").body());
        }
    }

    @Test void exceptionsAre500AndOnlyTheFirstResponseCounts() throws Exception {
        AtomicReference<Boolean> second = new AtomicReference<>();
        HttpInitializer strict = HttpInitializer.http()
                .handler(exchange -> {
                    if (exchange.request().uri().equals("/boom")) throw new IllegalStateException("boom");
                    exchange.respond(HttpResponseStatus.NO_CONTENT);
                    second.set(exchange.respond(HttpResponseStatus.OK));
                    assertThrows(IllegalArgumentException.class, () -> exchange.respond(HttpResponseStatus.CONTINUE));
                })
                .build();
        try (NetworkServer server = NetworkServer.start(LOCAL, strict)) {
            assertEquals(500, send(server, "GET", "/boom", null).statusCode());
            HttpResponse<String> once = send(server, "GET", "/once", null);
            assertEquals(204, once.statusCode());
            assertFalse(once.headers().firstValue("content-length").isPresent(), "204 carries no Content-Length");
            assertEquals(Boolean.FALSE, second.get());
        }
    }

    @Test void oversizedBodiesAreRejectedWith413() throws Exception {
        HttpInitializer small = HttpInitializer.http().maxContentLength(8)
                .handler(exchange -> exchange.respond(HttpResponseStatus.OK))
                .build();
        try (NetworkServer server = NetworkServer.start(LOCAL, small)) {
            assertEquals(200, send(server, "POST", "/", "12345678").statusCode());
            assertEquals(413, send(server, "POST", "/", "123456789").statusCode());
        }
    }

    @Test void pipelinedRequestsAreHandledOneAtATimeInOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        HttpInitializer ordered = HttpInitializer.http()
                .handler(exchange -> {
                    String path = exchange.path();
                    order.add("handle " + path);
                    if (path.equals("/slow")) LATER.schedule(() -> {
                        order.add("respond /slow");
                        exchange.respond(text(HttpResponseStatus.OK, "slow"));
                    }, 200, TimeUnit.MILLISECONDS);
                    else exchange.respond(text(HttpResponseStatus.OK, "fast"));
                })
                .build();
        try (NetworkServer server = NetworkServer.start(LOCAL, ordered);
             Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /slow HTTP/1.1\r\nHost: test\r\n\r\n"
                    + "GET /fast HTTP/1.1\r\nHost: test\r\n\r\n").getBytes(UTF_8));
            String responses = readUntil(socket.getInputStream(), "slow", "fast");
            assertTrue(responses.indexOf("slow") < responses.indexOf("fast"), "responses out of request order:\n" + responses);
            assertEquals(List.of("handle /slow", "respond /slow", "handle /fast"), order);
        }
    }

    @Test void handlersBeforeTheHttpHandlerMayAnswerRequests() throws Exception {
        HttpInitializer withHealth = HttpInitializer.http()
                .addLast("health", new Health())
                .handler(exchange -> exchange.respond(text(HttpResponseStatus.OK, "app")))
                .build();
        try (NetworkServer server = NetworkServer.start(LOCAL, withHealth)) {
            // Both go over one kept-alive connection: the second is read only after the first was answered.
            assertEquals("ok", send(server, "GET", "/health", null).body());
            assertEquals("app", send(server, "GET", "/game", null).body());
        }
    }

    @Test void servesHttps(@TempDir Path directory) throws Exception {
        char[] password = "changeit".toCharArray();
        Path store = TestCertificates.selfSignedKeyStore(directory, password);
        HttpInitializer secure = HttpInitializer.https(NetworkSsl.server(store, password))
                .handler(exchange -> exchange.respond(text(HttpResponseStatus.OK, "secure")))
                .build();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .sslContext(TestCertificates.trusting(store, password)).build();
        try (NetworkServer server = NetworkServer.start(LOCAL, secure)) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("https://localhost:" + server.port() + "/")).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("secure", response.body());
        }
    }

    @Test void contextPathScopesTheHandlerAndRelativizesPaths() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpInitializer admin = HttpInitializer.http().contextPath("/admin/")
                .handler(exchange -> {
                    seen.add(exchange.path());
                    exchange.respond(text(HttpResponseStatus.OK, exchange.path()));
                })
                .build();
        try (NetworkServer server = NetworkServer.start(LOCAL, admin)) {
            assertEquals("/users", send(server, "GET", "/admin/users?id=1", null).body());
            assertEquals("/", send(server, "GET", "/admin", null).body());
            assertEquals("/", send(server, "GET", "/admin/", null).body());
            assertEquals(404, send(server, "GET", "/adminx", null).statusCode());
            assertEquals(404, send(server, "GET", "/other/admin", null).statusCode());
            assertEquals(List.of("/users", "/", "/"), seen, "requests outside the context path never reach the handler");
        }
    }

    @Test void builderValidation() {
        assertThrows(IllegalStateException.class, () -> HttpInitializer.http().build());
        assertThrows(IllegalArgumentException.class, () -> HttpInitializer.http().maxContentLength(0));
        assertThrows(IllegalArgumentException.class, () -> HttpInitializer.http().addLast("http-handler", new Health()));
        for (String invalid : new String[] {"admin", "//admin", "/admin?x", "/ad min", "/admin/*"})
            assertThrows(IllegalArgumentException.class, () -> HttpInitializer.http().contextPath(invalid), invalid);
        HttpInitializer.http().contextPath("").contextPath("/").contextPath("/a/b");
    }

    /** Answers /health itself, as a native Netty handler in the addLast section. */
    @Sharable
    private static final class Health extends ChannelInboundHandlerAdapter {
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof FullHttpRequest request && request.uri().equals("/health")) {
                request.release();
                FullHttpResponse response = text(HttpResponseStatus.OK, "ok");
                HttpUtil.setContentLength(response, response.content().readableBytes());
                ctx.writeAndFlush(response);
                return;
            }
            ctx.fireChannelRead(message);
        }
    }

    /** Reads until every marker has arrived. */
    private static String readUntil(InputStream in, String... markers) throws Exception {
        StringBuilder text = new StringBuilder();
        byte[] buffer = new byte[1024];
        while (!java.util.Arrays.stream(markers).allMatch(text.toString()::contains)) {
            int n = in.read(buffer);
            if (n < 0) break;
            text.append(new String(buffer, 0, n, UTF_8));
        }
        return text.toString();
    }
}
