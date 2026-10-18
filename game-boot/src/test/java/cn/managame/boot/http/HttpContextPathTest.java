package cn.managame.boot.http;

import cn.managame.network.http.HttpResponseCallback;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Prefix mapping as a value, apart from any listener: what the delegate sees and who owns the request. */
class HttpContextPathTest {
    private static HttpResponseCallback releasing() {
        return new HttpResponseCallback() {
            @Override public boolean onResponse(FullHttpResponse response) { response.release(); return true; }
            @Override public boolean onFail(Throwable cause) { throw new AssertionError(cause); }
        };
    }

    @Test void mappingPreservesTheOriginalRequestAndItsReferenceEvenWhenTheDelegateThrows() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/query?id=7");
        try {
            var mapping = new HttpContextPath("/api", (mapped, _) -> {
                assertEquals("/query?id=7", mapped.uri());
                assertEquals("/api/query?id=7", request.uri());
                throw new IllegalStateException("delegate failed");
            });
            assertThrows(IllegalStateException.class, () -> mapping.dispatch(request, releasing()));
            assertEquals(1, request.refCnt());
            assertEquals("/api/query?id=7", request.uri());
        } finally { request.release(); }
    }

    @Test void anInvalidPrefixIsRejectedWhereItIsConfigured() {
        for (String invalid : List.of("api", "/api//v1", "/api//", "/api?x", "/api#x", "/api/%2f", "/./api"))
            assertThrows(IllegalArgumentException.class, () -> new HttpContextPath(invalid, (_, _) -> {}));
    }
}
