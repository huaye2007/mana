package cn.managame.example.network;

import cn.managame.network.http.HttpServer;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Callback completion on an application-owned business thread; no future-based response API. */
public final class HttpAsyncServerExample {
    private HttpAsyncServerExample() {}
    public static void main(String[] args) throws Exception { System.out.println(roundTrip("hello async HTTP/1.1")); }

    public static String roundTrip(String message) throws Exception {
        try (var business = Executors.newSingleThreadExecutor();
             var server = HttpServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0))
                     .asyncHandler((request, callback) -> {
                         // Decode inside the borrowed scope; the business task holds only immutable data.
                         String body = request.content().toString(StandardCharsets.UTF_8);
                         try {
                             business.execute(() -> {
                                 var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                         Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
                                 response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                                 callback.onResponse(response); // Always transfers ownership, even after disconnection.
                             });
                         } catch (RejectedExecutionException rejected) { callback.onFail(rejected); }
                     }).build();
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            server.start();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("Async echo failed: " + response.statusCode());
            return response.body();
        }
    }
}
