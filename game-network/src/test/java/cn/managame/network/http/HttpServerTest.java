package cn.managame.network.http;

import cn.managame.network.error.NetworkException;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.security.KeyStore;
import javax.net.ssl.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class HttpServerTest {
    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        System.setProperty("io.netty.leakDetection.level", "paranoid");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/http-tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent()); Files.writeString(marker, "test-only TCP selector pipe");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    @Test void keepAliveChunkedBodyAndReferenceOwnership() throws Exception {
        List<FullHttpRequest> borrowed = new CopyOnWriteArrayList<>();
        List<FullHttpResponse> transferred = new CopyOnWriteArrayList<>();
        try (var server = HttpServer.builder().bindAddress(LOCAL).handler(request -> {
            borrowed.add(request);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    request.content().retainedDuplicate());
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 999); // Framework fixes normal response framing.
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
            transferred.add(response); return response;
        }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send("POST /echo?x=1 HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "3\r\nabc\r\n2\r\nde\r\n0\r\nX-Trailer: yes\r\n\r\n");
                Reply first = wire.reply(false);
                assertEquals(200, first.status()); assertEquals("abcde", first.body());
                assertEquals("5", first.headers().get("content-length"));
                assertFalse(first.headers().containsKey("transfer-encoding"));
                wire.send("POST /echo HTTP/1.1\r\nHost: local\r\nContent-Length: 3\r\nConnection: close\r\n\r\nbye");
                Reply second = wire.reply(false);
                assertEquals("bye", second.body()); assertEquals("close", second.headers().get("connection"));
                assertEquals(-1, wire.input.read());
            }
            assertEquals(2, borrowed.size());
            assertEquals("yes", borrowed.getFirst().trailingHeaders().get("X-Trailer"));
            for (var response : transferred) assertEquals(0, response.refCnt());
            for (var request : borrowed) assertEquals(0, request.refCnt());
        }
    }

    @Test void nativeHandlersCanRouteOrRejectAndUnmatchedRequestsUseDefault404() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<FullHttpRequest> consumed = new CopyOnWriteArrayList<>();
        try (var server = HttpServer.builder().bindAddress(LOCAL).pipeline(p -> p.addLast("routes", new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                FullHttpRequest request = (FullHttpRequest) message;
                calls.incrementAndGet();
                if (!request.uri().equals("/native") && !request.uri().equals("/private")) {
                    ctx.fireChannelRead(message); return;
                }
                consumed.add(request);
                try {
                    FullHttpResponse response = request.uri().equals("/private")
                            ? response(HttpResponseStatus.UNAUTHORIZED, "denied") : response(HttpResponseStatus.OK, "native");
                    HttpUtil.setContentLength(response, response.content().readableBytes());
                    ctx.writeAndFlush(response);
                } finally { ReferenceCountUtil.release(request); }
            }
        })).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/native")); assertEquals("native", wire.reply(false).body());
                wire.send(request("GET", "/unknown")); assertEquals(404, wire.reply(false).status());
                wire.send("GET /private HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n" + request("GET", "/native"));
                Reply rejected = wire.reply(false);
                assertEquals(401, rejected.status()); assertEquals("close", rejected.headers().get("connection"));
                assertEquals(-1, wire.input.read());
            }
            assertEquals(3, calls.get(), "The request after closure must not reach native business handlers");
            for (var request : consumed) assertEquals(0, request.refCnt());
        }
    }

    @Test void nativeCorsAndCompressionHandlePreflightAndTransformResponses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String body = "游戏服务器 response ".repeat(50);
        var cors = CorsConfigBuilder.forOrigin("https://admin.example")
                .allowedRequestMethods(HttpMethod.GET).allowedRequestHeaders("content-type").build();
        try (var server = HttpServer.builder().bindAddress(LOCAL)
                .pipeline(p -> p.addLast("cors", new CorsHandler(cors)))
                .pipeline(p -> p.addLast("compression", new HttpContentCompressor()))
                .handler(request -> { calls.incrementAndGet(); return response(HttpResponseStatus.OK, body); }).build();
             var client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()) {
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort() + "/");
            var preflight = client.send(java.net.http.HttpRequest.newBuilder(uri)
                    .header("Origin", "https://admin.example").header("Access-Control-Request-Method", "GET")
                    .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, preflight.statusCode()); assertEquals(0, calls.get());
            assertEquals("https://admin.example", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            var compressed = client.send(java.net.http.HttpRequest.newBuilder(uri)
                    .header("Origin", "https://admin.example").header("Accept-Encoding", "gzip").GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, compressed.statusCode()); assertEquals(1, calls.get());
            assertEquals("gzip", compressed.headers().firstValue("Content-Encoding").orElseThrow());
            assertEquals("https://admin.example", compressed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            try (var decoded = new GZIPInputStream(new ByteArrayInputStream(compressed.body()))) {
                assertEquals(body, new String(decoded.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test void offloadedExtensionsUseTheSameOrderedExecutor() throws Exception {
        var executor = new DefaultEventExecutorGroup(1);
        try (var server = HttpServer.builder().bindAddress(LOCAL).executorGroup(executor)
                .pipeline(p -> p.addLast(executor, "rewrite", new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                        assertSame(ctx.executor(), ctx.pipeline().context("http-codec").executor());
                        ((FullHttpRequest) message).setUri("/rewritten"); ctx.fireChannelRead(message);
                    }
                })).handler(request -> response(HttpResponseStatus.OK, request.uri())).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/")); assertEquals("/rewritten", wire.reply(false).body());
            }
        }
        try (var invalid = HttpServer.builder().bindAddress(LOCAL).executorGroup(executor)
                .pipeline(p -> p.addLast("wrong-executor", new ChannelInboundHandlerAdapter())).build()) {
            invalid.start();
            try (var wire = new Wire(invalid)) { assertEquals(-1, wire.input.read()); }
        } finally { executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
    }

    @Test void headAndBodylessStatusesPreserveNextResponseBoundary() throws Exception {
        try (var server = HttpServer.builder().bindAddress(LOCAL).handler(request -> {
            HttpResponseStatus status = request.uri().equals("/empty") ? HttpResponseStatus.NO_CONTENT
                    : request.uri().equals("/reset") ? HttpResponseStatus.RESET_CONTENT
                    : request.uri().equals("/cached") ? HttpResponseStatus.NOT_MODIFIED : HttpResponseStatus.OK;
            return response(status, "hello");
        }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("HEAD", "/head") + request("GET", "/empty")
                        + request("GET", "/reset") + request("GET", "/cached") + request("GET", "/next"));
                Reply head = wire.reply(true);
                assertEquals("5", head.headers().get("content-length")); assertEquals("", head.body());
                Reply empty = wire.reply(false);
                assertEquals(204, empty.status()); assertFalse(empty.headers().containsKey("content-length"));
                Reply reset = wire.reply(false);
                assertEquals(205, reset.status()); assertEquals("0", reset.headers().get("content-length"));
                Reply cached = wire.reply(false);
                assertEquals(304, cached.status()); assertEquals("5", cached.headers().get("content-length"));
                assertEquals("hello", wire.reply(false).body());
            }
        }
    }

    @Test void errorsRejectBeforeApplicationAndClose() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = HttpServer.builder().bindAddress(LOCAL).maxContentLength(4)
                .maxInitialLineLength(64).maxHeaderSize(128).handler(request -> {
                    calls.incrementAndGet(); return response(HttpResponseStatus.OK, "ok");
                }).build()) {
            server.start();
            assertRejected(server, "GET / HTTP/1.1\r\n\r\n", 400);
            assertRejected(server, "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n", 400);
            assertRejected(server, "GET / HTTP/1.0\r\n\r\n", 505);
            assertRejected(server, "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", 505);
            assertRejected(server, request("CONNECT", "example:80"), 405);
            assertRejected(server, "GET / HTTP/1.1\r\nHost: a\r\nUpgrade: websocket\r\n\r\n", 400);
            assertRejected(server, "GET / HTTP/1.1\r\nHost: a\r\nBad Header: x\r\n\r\n", 400);
            assertRejected(server, request("GET", "/" + "x".repeat(100)), 400);
            assertRejected(server, "GET / HTTP/1.1\r\nHost: a\r\nX: " + "x".repeat(180) + "\r\n\r\n", 400);
            assertRejected(server, "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nabcde", 413);
            assertRejected(server, "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabcde\r\n0\r\n\r\n", 413);
            assertRejected(server, "POST / HTTP/1.1\r\nHost: a\r\nExpect: strange\r\nContent-Length: 0\r\n\r\n", 417);
            assertRejected(server, "POST / HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n", 413);
            assertEquals(0, calls.get());
        }
    }

    @Test void continueAndApplicationResponsesRemainOrderedWhenOffloaded() throws Exception {
        var executor = new DefaultEventExecutorGroup(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> callback = new AtomicReference<>();
        AtomicReference<Channel> channel = new AtomicReference<>();
        try (var server = HttpServer.builder().bindAddress(LOCAL).executorGroup(executor)
                .pipeline(p -> channel.set(p.channel())).handler(request -> {
                    callback.set(Thread.currentThread());
                    if (request.uri().equals("/first")) {
                        entered.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    return response(HttpResponseStatus.OK, request.uri());
                }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/first"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                wire.send("POST /second HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\n");
                // Socket EventLoop keeps running while the ordered HTTP executor is blocked.
                channel.get().eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
                release.countDown();
                assertEquals("/first", wire.reply(false).body());
                assertEquals(100, wire.reply(false).status());
                wire.send("abc"); assertEquals("/second", wire.reply(false).body());
                assertFalse(channel.get().eventLoop().inEventLoop(callback.get()));
                executor.submit(() -> { assertThrows(IllegalStateException.class, server::close); })
                        .get(5, TimeUnit.SECONDS);
            }
            server.close(); assertFalse(executor.isShuttingDown());
        } finally { release.countDown(); executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
    }

    @Test void pipelinedOversizeResponseFollowsEarlierApplicationResponse() throws Exception {
        var executor = new DefaultEventExecutorGroup(1);
        try (var server = HttpServer.builder().bindAddress(LOCAL).executorGroup(executor).maxContentLength(4)
                .handler(request -> response(HttpResponseStatus.OK, "first")).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/first")
                        + "POST /second HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nabcde");
                assertEquals("first", wire.reply(false).body());
                assertEquals(413, wire.reply(false).status()); assertEquals(-1, wire.input.read());
            }
        } finally { executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
    }

    @Test void closeRequestDoesNotExecuteLaterPipelinedBusiness() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = HttpServer.builder().bindAddress(LOCAL).handler(request -> {
            calls.incrementAndGet(); return response(HttpResponseStatus.OK, "ok");
        }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n" + request("GET", "/later"));
                assertEquals(200, wire.reply(false).status()); assertEquals(-1, wire.input.read());
            }
            assertEquals(1, calls.get());
        }
    }

    @Test void handlerFailureAndInvalidResponseReleaseRequests() throws Exception {
        for (String failure : List.of("throw", "null", "informational")) {
            AtomicReference<FullHttpRequest> borrowed = new AtomicReference<>();
            AtomicReference<FullHttpResponse> transferred = new AtomicReference<>();
            AtomicReference<Channel> channel = new AtomicReference<>();
            try (var server = HttpServer.builder().bindAddress(LOCAL).pipeline(p -> channel.set(p.channel())).handler(request -> {
                borrowed.set(request);
                if (failure.equals("throw")) throw new IllegalStateException("test handler failure");
                if (failure.equals("null")) return null;
                var response = response(HttpResponseStatus.CONTINUE, "invalid"); transferred.set(response); return response;
            }).build()) {
                server.start(); assertRejected(server, "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\n\r\nx", 500);
                // Receiving the failure response/EOF does not prove the borrowed callback scope has returned.
                channel.get().eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(0, borrowed.get().refCnt());
                if (transferred.get() != null) assertEquals(0, transferred.get().refCnt());
            }
        }
    }

    @Test void readTimeoutClosesIncompleteRequestAndIdleKeepAlive() throws Exception {
        try (var server = HttpServer.builder().bindAddress(LOCAL).readTimeoutMillis(150)
                .handler(request -> response(HttpResponseStatus.OK, "ok")).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nx");
                assertEquals(-1, wire.input.read());
            }
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/")); assertEquals(200, wire.reply(false).status());
                assertEquals(-1, wire.input.read());
            }
        }
    }

    @Test void disconnectDuringOffloadedHandlerReleasesReturnedResponse() throws Exception {
        var executor = new DefaultEventExecutorGroup(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<FullHttpRequest> borrowed = new AtomicReference<>();
        AtomicReference<FullHttpResponse> transferred = new AtomicReference<>();
        try (var server = HttpServer.builder().bindAddress(LOCAL).executorGroup(executor).handler(request -> {
            borrowed.set(request); entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            var response = response(HttpResponseStatus.OK, "finished"); transferred.set(response); return response;
        }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\n\r\nx");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                server.close(); // Owned transport closes while external business execution continues.
                assertFalse(executor.isShuttingDown());
                release.countDown(); executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(0, borrowed.get().refCnt()); assertEquals(0, transferred.get().refCnt());
            }
        } finally { release.countDown(); executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
    }

    @Test void nativeTlsServesHttpAndRejectsPlaintext() throws Exception {
        Path store = Path.of("target/http-test.p12").toAbsolutePath(); Files.deleteIfExists(store);
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "http", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "http-test", "-noprompt")
                .redirectErrorStream(true).redirectOutput(Path.of("target/http-keytool.log").toFile()).start();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, keytool.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "http-test".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "http-test".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(keys);
        var tls = SslContextBuilder.forServer(kmf).sslProvider(SslProvider.JDK).build();
        SSLContext client = SSLContext.getInstance("TLS"); client.init(null, tmf.getTrustManagers(), null);
        AtomicInteger calls = new AtomicInteger();
        try (var server = HttpServer.builder().bindAddress(LOCAL)
                .pipeline(p -> p.addFirst("ssl", tls.newHandler(p.channel().alloc())))
                .handler(request -> { calls.incrementAndGet(); return response(HttpResponseStatus.OK, "ok"); }).build()) {
            server.start();
            try (var socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters); socket.connect(server.localAddress(), 3000); socket.setSoTimeout(5000);
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(reply.startsWith("HTTP/1.1 200")); assertTrue(reply.endsWith("ok"));
            }
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/"));
                try { assertEquals(-1, wire.input.read()); }
                catch (SocketException reset) { /* TLS rejects before any HTTP business processing. */ }
            }
            assertEquals(1, calls.get());
        }
    }

    @Test void listenerLifecycleSnapshotsAndBorrowedGroups() throws Exception {
        var group = new NioEventLoopGroup(1);
        var builder = HttpServer.builder().bindAddress(LOCAL).bossGroup(group).workerGroup(group)
                .handler(request -> response(HttpResponseStatus.OK, "first"));
        try (var first = builder.build()) {
            builder.handler(request -> response(HttpResponseStatus.OK, "second"));
            try (var second = builder.build()) {
                assertNull(first.localAddress()); first.start(); second.start();
                try (var wire = new Wire(first); var other = new Wire(second)) {
                    wire.send(request("GET", "/")); assertEquals("first", wire.reply(false).body());
                    other.send(request("GET", "/")); assertEquals("second", other.reply(false).body());
                    group.submit(() -> assertThrows(IllegalStateException.class, first::close)).sync();
                    first.close(); assertFalse(group.isShuttingDown());
                    // Borrowed worker owns existing sockets; listener closure does not traverse them.
                    wire.send(request("GET", "/")); assertEquals("first", wire.reply(false).body());
                    assertThrows(IllegalStateException.class, first::start);
                }
            }
        } finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
        try (var running = HttpServer.builder().bindAddress(LOCAL).handler(request -> response(HttpResponseStatus.OK, "ok")).build()) {
            running.start();
            try (var failed = HttpServer.builder().bindAddress(running.localAddress()).handler(request -> response(HttpResponseStatus.OK, "ok")).build()) {
                assertThrows(NetworkException.class, failed::start); assertThrows(IllegalStateException.class, failed::start);
            }
        }
    }

    @Test void configurationRejectsInvalidLimitsAndUnorderedPipelineOption() {
        assertThrows(IllegalStateException.class, () -> HttpServer.builder().build());
        assertThrows(IllegalArgumentException.class, () -> HttpServer.builder().maxContentLength(0));
        assertThrows(IllegalArgumentException.class, () -> HttpServer.builder().maxHeaderSize(-1));
        assertThrows(IllegalArgumentException.class, () -> HttpServer.builder().readTimeoutMillis(-1));
        var executor = new DefaultEventExecutorGroup(1);
        try {
            assertThrows(IllegalArgumentException.class, () -> HttpServer.builder().bindAddress(LOCAL)
                    .handler(request -> response(HttpResponseStatus.OK, "ok")).executorGroup(executor)
                    .childOption(ChannelOption.SINGLE_EVENTEXECUTOR_PER_GROUP, false).build());
        } finally { executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly(); }
    }

    @Test void asyncCompletionDoesNotBlockTransportAndUsesIndependentResponseOwnership() throws Exception {
        var business = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<FullHttpRequest> borrowed = new AtomicReference<>();
        AtomicReference<FullHttpResponse> transferred = new AtomicReference<>();
        AtomicReference<Channel> channel = new AtomicReference<>();
        try (var server = HttpServer.builder().bindAddress(LOCAL).pipeline(p -> channel.set(p.channel()))
                .asyncHandler((request, callback) -> {
                    borrowed.set(request);
                    var body = request.content().copy();
                    business.execute(() -> {
                        try {
                            entered.countDown();
                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, body);
                            transferred.set(response);
                            assertTrue(callback.onResponse(response));
                        } catch (InterruptedException e) { body.release(); callback.onFail(e); }
                    });
                }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nabc");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                channel.get().eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(0, borrowed.get().refCnt(), "The callback scope does not extend request ownership");
                finish.countDown();
                assertEquals("abc", wire.reply(false).body());
                channel.get().eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(0, transferred.get().refCnt());
            }
        } finally { finish.countDown(); business.shutdownNow(); }
    }

    @Test void asyncFirstResponsePrecedesContinueAndProtocolRejections() throws Exception {
        for (String next : List.of("continue", "oversize", "expectation", "invalid")) {
            var executor = new DefaultEventExecutorGroup(1);
            AtomicReference<HttpResponseCallback> first = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            try (var server = HttpServer.builder().bindAddress(LOCAL).executorGroup(executor).maxContentLength(4)
                    .asyncHandler((request, callback) -> {
                        calls.incrementAndGet();
                        if (request.uri().equals("/first")) { first.set(callback); entered.countDown(); }
                        else callback.onResponse(response(HttpResponseStatus.OK, "second"));
                    }).build()) {
                server.start();
                try (var wire = new Wire(server)) {
                    String second = switch (next) {
                        case "continue" -> "POST /second HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\n";
                        case "oversize" -> "POST /second HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nabcde";
                        case "expectation" -> "POST /second HTTP/1.1\r\nHost: a\r\nExpect: unsupported\r\nContent-Length: 3\r\n\r\n";
                        default -> "GET /second HTTP/1.1\r\n\r\n";
                    };
                    wire.send(request("GET", "/first") + second);
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    wire.socket.setSoTimeout(150);
                    assertThrows(SocketTimeoutException.class, wire.input::read, next);
                    assertEquals(1, calls.get());
                    assertTrue(first.get().onResponse(response(HttpResponseStatus.OK, "first")));
                    wire.socket.setSoTimeout(5000);
                    assertEquals("first", wire.reply(false).body());
                    if (next.equals("continue")) {
                        assertEquals(100, wire.reply(false).status());
                        wire.send("abc"); assertEquals("second", wire.reply(false).body());
                        assertEquals(2, calls.get());
                    } else {
                        assertEquals(switch (next) { case "oversize" -> 413; case "expectation" -> 417; default -> 400; }, wire.reply(false).status());
                        assertEquals(-1, wire.input.read()); assertEquals(1, calls.get());
                    }
                }
            } finally { executor.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
        }
    }

    @Test void asyncPipelinedBusinessWaitsForFirstCompletionAndOtherConnectionsProgress() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<HttpResponseCallback> first = new AtomicReference<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        try (var server = HttpServer.builder().bindAddress(LOCAL).asyncHandler((request, callback) -> {
            calls.add(request.uri());
            if (request.uri().equals("/first")) { first.set(callback); entered.countDown(); }
            else callback.onResponse(response(HttpResponseStatus.OK, request.uri()));
        }).build()) {
            server.start();
            try (var wire = new Wire(server); var other = new Wire(server)) {
                wire.send(request("GET", "/first") + request("GET", "/second"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                other.send(request("GET", "/other")); assertEquals("/other", other.reply(false).body());
                assertEquals(List.of("/first", "/other"), calls);
                first.get().onResponse(response(HttpResponseStatus.OK, "first"));
                assertEquals("first", wire.reply(false).body()); assertEquals("/second", wire.reply(false).body());
                wire.send(request("GET", "/third")); assertEquals("/third", wire.reply(false).body());
            }
        }
    }

    @Test void asyncCompletionRaceHasOneWinnerAndConsumesEveryResponseReference() throws Exception {
        AtomicReference<HttpResponseCallback> callback = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), race = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try (var server = HttpServer.builder().bindAddress(LOCAL).asyncHandler((request, result) -> {
            callback.set(result); entered.countDown();
        }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/")); assertTrue(entered.await(5, TimeUnit.SECONDS));
                var a = response(HttpResponseStatus.OK, "a"); var b = response(HttpResponseStatus.OK, "b");
                var first = workers.submit(() -> { race.await(); return callback.get().onResponse(a); });
                var second = workers.submit(() -> { race.await(); return callback.get().onResponse(b); });
                race.countDown();
                assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                assertTrue(Set.of("a", "b").contains(wire.reply(false).body()));
                assertFalse(callback.get().onFail(new IllegalStateException("late failure")));
                assertEquals(0, a.refCnt()); assertEquals(0, b.refCnt());
            }
        } finally { race.countDown(); workers.shutdownNow(); }
    }

    @Test void asyncFailureClosesAndNeverDispatchesLaterPipelinedRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<HttpResponseCallback> callback = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        try (var server = HttpServer.builder().bindAddress(LOCAL).asyncHandler((request, result) -> {
            calls.incrementAndGet(); callback.set(result); entered.countDown();
        }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/first") + request("GET", "/second"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(callback.get().onFail(new IllegalStateException("async test failure")));
                assertEquals(500, wire.reply(false).status()); assertEquals(-1, wire.input.read());
                var late = response(HttpResponseStatus.OK, "late");
                assertFalse(callback.get().onResponse(late)); assertEquals(0, late.refCnt());
                assertEquals(1, calls.get());
            }
        }
    }

    @Test void lateAsyncCompletionAfterOwnedShutdownReleasesResponseAndDoesNotCancelBusiness() throws Exception {
        AtomicReference<HttpResponseCallback> callback = new AtomicReference<>();
        AtomicReference<FullHttpRequest> borrowed = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        try (var server = HttpServer.builder().bindAddress(LOCAL).asyncHandler((request, result) -> {
            borrowed.set(request); callback.set(result); entered.countDown();
        }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\n\r\nx");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                server.close();
                assertEquals(0, borrowed.get().refCnt());
                var late = response(HttpResponseStatus.OK, "late");
                assertTrue(callback.get().onResponse(late)); assertEquals(0, late.refCnt());
                assertEquals(-1, wire.input.read());
            }
        }
    }

    @Test void asyncReadTimeoutClosesWithoutCompletingApplicationCallback() throws Exception {
        AtomicReference<HttpResponseCallback> callback = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        try (var server = HttpServer.builder().bindAddress(LOCAL).readTimeoutMillis(150)
                .asyncHandler((request, result) -> { callback.set(result); entered.countDown(); }).build()) {
            server.start();
            try (var wire = new Wire(server)) {
                wire.send(request("GET", "/")); assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(-1, wire.input.read());
                var late = response(HttpResponseStatus.OK, "late");
                assertTrue(callback.get().onResponse(late)); assertEquals(0, late.refCnt());
            }
        }
    }

    @Test void synchronousAndAsynchronousHandlerSettingsReplaceEachOther() throws Exception {
        var builder = HttpServer.builder().bindAddress(LOCAL)
                .handler(request -> response(HttpResponseStatus.OK, "sync"));
        try (var sync = builder.build();
             var async = builder.asyncHandler((request, callback) -> callback.onResponse(response(HttpResponseStatus.OK, "async"))).build();
             var replacement = builder.handler(request -> response(HttpResponseStatus.OK, "replacement")).build()) {
            for (var server : List.of(sync, async, replacement)) server.start();
            try (var a = new Wire(sync); var b = new Wire(async); var c = new Wire(replacement)) {
                a.send(request("GET", "/")); b.send(request("GET", "/")); c.send(request("GET", "/"));
                assertEquals("sync", a.reply(false).body()); assertEquals("async", b.reply(false).body());
                assertEquals("replacement", c.reply(false).body());
            }
        }
    }

    @Test void bufferedRequestsCannotBeOvertakenByInputArrivingDuringCompletion() {
        List<String> calls = new ArrayList<>();
        AtomicReference<HttpResponseCallback> first = new AtomicReference<>();
        var channel = new EmbeddedChannel();
        HttpServerTransport.configure(channel.pipeline(), null, (request, callback) -> {
            calls.add(request.uri());
            if (request.uri().equals("/first")) first.set(callback);
            else callback.onResponse(response(HttpResponseStatus.OK, request.uri()));
        }, 1024, 4096, 8192, List.of());
        try {
            channel.writeInbound(Unpooled.copiedBuffer(request("GET", "/first") + request("GET", "/second"), StandardCharsets.US_ASCII));
            channel.eventLoop().execute(() -> {
                first.get().onResponse(response(HttpResponseStatus.OK, "first"));
                // New input arrives before the scheduled advancement of previously buffered input.
                channel.pipeline().fireChannelRead(Unpooled.copiedBuffer(request("GET", "/third"), StandardCharsets.US_ASCII));
            });
            channel.runPendingTasks();
            assertEquals(List.of("/first", "/second", "/third"), calls);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void closingWhileAsyncResponseIsPendingReleasesBufferedRequestContent() {
        AtomicReference<HttpResponseCallback> callback = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        var channel = new EmbeddedChannel();
        HttpServerTransport.configure(channel.pipeline(), null, (request, result) -> {
            callback.set(result); calls.incrementAndGet();
        }, 1024, 4096, 8192, List.of());
        var input = Unpooled.copiedBuffer(request("GET", "/first")
                + "POST /second HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nabc", StandardCharsets.US_ASCII);
        try {
            channel.writeInbound(input);
            assertEquals(1, calls.get());
            channel.close(); channel.runPendingTasks();
            assertEquals(0, input.refCnt(), "All buffered content must be released on disconnection");
            var late = response(HttpResponseStatus.OK, "late");
            assertTrue(callback.get().onResponse(late)); assertEquals(0, late.refCnt());
            assertEquals(1, calls.get());
        } finally { channel.finishAndReleaseAll(); }
    }

    private static String request(String method, String path) { return method + " " + path + " HTTP/1.1\r\nHost: local\r\n\r\n"; }
    private static FullHttpResponse response(HttpResponseStatus status, String body) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
    }
    private static void assertRejected(HttpServer server, String request, int status) throws Exception {
        try (var wire = new Wire(server)) {
            wire.send(request); assertEquals(status, wire.reply(false).status(), request); assertEquals(-1, wire.input.read());
        }
    }
    private record Reply(int status, Map<String, String> headers, String body) {}
    private static final class Wire implements AutoCloseable {
        final Socket socket = new Socket();
        final InputStream input;
        Wire(HttpServer server) throws IOException {
            socket.connect(server.localAddress(), 3000); socket.setSoTimeout(5000); input = socket.getInputStream();
        }
        void send(String text) throws IOException { socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush(); }
        Reply reply(boolean head) throws IOException {
            String statusLine = line(); int status = Integer.parseInt(statusLine.split(" ")[1]);
            Map<String, String> headers = new HashMap<>();
            for (String header; !(header = line()).isEmpty();) {
                int separator = header.indexOf(':'); headers.put(header.substring(0, separator).toLowerCase(Locale.ROOT), header.substring(separator + 1).trim());
            }
            int length = head || status < 200 || status == 204 || status == 304 ? 0 : Integer.parseInt(headers.getOrDefault("content-length", "0"));
            byte[] bytes = input.readNBytes(length); if (bytes.length != length) throw new EOFException("Incomplete HTTP body");
            return new Reply(status, headers, new String(bytes, StandardCharsets.UTF_8));
        }
        String line() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int next; (next = input.read()) != -1;) {
                if (next == '\n') return line.toString(StandardCharsets.US_ASCII).replace("\r", "");
                line.write(next);
            }
            throw new EOFException("Incomplete HTTP headers");
        }
        public void close() throws IOException { socket.close(); }
    }
}
