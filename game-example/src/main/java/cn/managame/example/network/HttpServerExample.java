package cn.managame.example.network;

import cn.managame.network.http.HttpServer;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Independent HTTP/1.1 server; the JDK client is only a runnable example caller. */
public final class HttpServerExample {
    private HttpServerExample() {}
    public static void main(String[] args) throws Exception { System.out.println(roundTrip("hello HTTP/1.1")); }

    public static String roundTrip(String message) throws Exception {
        try (var server = HttpServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0))
                .pipeline(p -> p.addLast("compression", new HttpContentCompressor()))
                .handler(request -> {
                    String path = new QueryStringDecoder(request.uri()).path();
                    FullHttpResponse response;
                    if (request.method().equals(HttpMethod.GET) && path.equals("/health")) {
                        response = text(HttpResponseStatus.OK, "ok");
                    } else if (request.method().equals(HttpMethod.POST) && path.equals("/echo")) {
                        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                request.content().retainedDuplicate());
                    } else response = text(HttpResponseStatus.NOT_FOUND, "not found");
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                    return response;
                }).build();
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            URI base = URI.create("http://127.0.0.1:" + port);
            var health = client.send(HttpRequest.newBuilder(base.resolve("/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (health.statusCode() != 200 || !health.body().equals("ok"))
                throw new IllegalStateException("Health check failed");
            var response = client.send(HttpRequest.newBuilder(base.resolve("/echo"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("Echo failed: " + response.statusCode());
            return response.body();
        }
    }
    private static FullHttpResponse text(HttpResponseStatus status, String text) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }
}
