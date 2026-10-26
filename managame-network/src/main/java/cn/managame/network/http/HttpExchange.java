package cn.managame.network.http;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.SocketAddress;

/**
 * One request and its response. The exchange may be answered later and from any thread; its request is
 * available only until {@link HttpHandler#handle} returns.
 */
public interface HttpExchange {
    /** The aggregated request. It is released when HttpHandler.handle returns; retain it to use it afterwards. */
    FullHttpRequest request();

    /**
     * The request path below the context path, without query string and still percent-encoded: {@code /users}
     * for {@code /admin/users?id=1} under context path {@code /admin}, and {@code /} for {@code /admin} itself.
     */
    String path();

    /**
     * Sends the final response and takes ownership of it; Content-Length is set from the content when absent.
     * Returns false, releasing the response, when the request was already answered. A 1xx status is rejected
     * with IllegalArgumentException.
     */
    boolean respond(FullHttpResponse response);

    /** An empty response with {@code status}. */
    default boolean respond(HttpResponseStatus status) {
        return respond(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status));
    }

    /** The peer of the connection, for example to restrict an internal endpoint to known addresses. */
    SocketAddress remoteAddress();
}
