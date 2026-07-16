package cn.managame.network.http.netty;

import cn.managame.network.http.IHttpHandler;
import cn.managame.network.http.IHttpResponder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NettyHttpDispatcherTest {

    @Test
    void sameBusinessHandlerServesHttp1AndHttp2() {
        AtomicInteger calls = new AtomicInteger();
        IHttpHandler handler = (request, responder) -> {
            calls.incrementAndGet();
            assertEquals("POST", request.method());
            assertEquals("/echo", request.uri());
            assertEquals("request", new String(request.body(), StandardCharsets.UTF_8));
            responder.text("response");
        };

        verifyHttp1(handler);
        verifyHttp2(handler);
        assertEquals(2, calls.get());
    }

    @Test
    void asynchronousHttp1ResponsesAreWrittenInRequestOrder() {
        AtomicReference<IHttpResponder> first = new AtomicReference<>();
        AtomicReference<IHttpResponder> second = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new NettyHttpDispatcher((request, responder) -> {
            if ("/first".equals(request.uri())) {
                first.set(responder);
            } else {
                second.set(responder);
            }
        }, false));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/first"));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second"));
        second.get().text("second");
        assertNull(channel.readOutbound());

        first.get().text("first");
        FullHttpResponse firstResponse = channel.readOutbound();
        FullHttpResponse secondResponse = channel.readOutbound();
        assertEquals("first", firstResponse.content().toString(StandardCharsets.UTF_8));
        assertEquals("second", secondResponse.content().toString(StandardCharsets.UTF_8));
        firstResponse.release();
        secondResponse.release();
        channel.finishAndReleaseAll();
    }

    private static void verifyHttp1(IHttpHandler handler) {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyHttpDispatcher(handler, false));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/echo",
                Unpooled.copiedBuffer("request", StandardCharsets.UTF_8)));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        assertEquals("response", response.content().toString(StandardCharsets.UTF_8));
        response.release();
        channel.finishAndReleaseAll();
    }

    private static void verifyHttp2(IHttpHandler handler) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new Http2StreamFrameToHttpObjectCodec(true),
                new io.netty.handler.codec.http.HttpObjectAggregator(1024),
                new NettyHttpDispatcher(handler, true));
        channel.writeInbound(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                .method("POST").path("/echo").scheme("http").authority("localhost"), false));
        channel.writeInbound(new DefaultHttp2DataFrame(
                Unpooled.copiedBuffer("request", StandardCharsets.UTF_8), true));

        Http2HeadersFrame headers = channel.readOutbound();
        Http2DataFrame data = channel.readOutbound();
        assertEquals("200", headers.headers().status().toString());
        assertEquals("response", new String(ByteBufUtil.getBytes(data.content()), StandardCharsets.UTF_8));
        data.release();
        channel.finishAndReleaseAll();
    }
}
