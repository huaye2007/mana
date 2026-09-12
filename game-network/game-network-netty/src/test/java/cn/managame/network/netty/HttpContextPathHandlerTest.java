package cn.managame.network.netty;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;

import org.junit.jupiter.api.Test;

class HttpContextPathHandlerTest {
    @Test
    void stripsOnlyTheLiteralPrefixAndPreservesRawQuery() {
        var channel = new EmbeddedChannel(new HttpContextPathHandler("/game"));
        try {
            for (var pair :
                    new String[][] {
                        {"/game", "/"},
                        {"/game?x=%2F+", "/?x=%2F+"},
                        {"/game/login?next=/game/a", "/login?next=/game/a"},
                        {"/game/a%2Fb", "/a%2Fb"}
                    }) {
                var request =
                        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, pair[0]);
                assertTrue(channel.writeInbound(request));
                FullHttpRequest forwarded = channel.readInbound();
                assertSame(request, forwarded);
                assertEquals(pair[1], forwarded.uri());
                forwarded.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsOtherContextsAndReleasesBodiesWithoutLosingNextRequest() {
        var channel = new EmbeddedChannel(new HttpContextPathHandler("/game"));
        try {
            for (String path :
                    new String[] {"/", "/game2/login", "/other?next=/game", "/%67ame/login"}) {
                var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path);
                HttpUtil.setTransferEncodingChunked(request, true);
                assertFalse(channel.writeInbound(request));
                FullHttpResponse response = channel.readOutbound();
                assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
                assertTrue(HttpUtil.isKeepAlive(response));
                response.release();
                var chunk = new DefaultHttpContent(Unpooled.buffer().writeByte(1));
                var last = new DefaultLastHttpContent(Unpooled.buffer().writeByte(2));
                assertFalse(channel.writeInbound(chunk, last));
                assertEquals(0, chunk.refCnt());
                assertEquals(0, last.refCnt());
                var accepted =
                        new DefaultFullHttpRequest(
                                HttpVersion.HTTP_1_1, HttpMethod.GET, "/game/ok");
                assertTrue(channel.writeInbound(accepted));
                assertSame(accepted, channel.readInbound());
                assertEquals("/ok", accepted.uri());
                accepted.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsExpectContinueWithoutWaitingForBody() {
        var channel = new EmbeddedChannel(new HttpContextPathHandler("/game"));
        try {
            var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/other");
            HttpUtil.set100ContinueExpected(request, true);
            HttpUtil.setContentLength(request, 10);
            assertFalse(channel.writeInbound(request));
            FullHttpResponse response = channel.readOutbound();
            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
            assertFalse(HttpUtil.isKeepAlive(response));
            response.release();
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void validatesContextPathAtConfigurationTime() {
        for (String path :
                new String[] {
                    "game",
                    "//game",
                    "/a//b",
                    "/a/../b",
                    "/./game",
                    "/game?x=1",
                    "/game#x",
                    "/%67ame",
                    "/game name",
                    "/a\\b"
                })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> HttpNetworkServer.builder().contextPath(path),
                    path);
        assertThrows(
                NullPointerException.class, () -> HttpNetworkServer.builder().contextPath(null));
        for (String path : new String[] {"", "/", "/game", "/game/", "/my-game/v1.0"})
            assertDoesNotThrow(() -> HttpNetworkServer.builder().contextPath(path));
    }
}
