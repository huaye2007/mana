package cn.managame.runtime.http;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_CLOSED;
import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;

import cn.managame.FrameworkErrorCodes;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.lang.reflect.Type;

/**
 * How {@link HttpMethod} endpoints read their argument from a request and how their results become responses,
 * for example JSON with the application's own mapper:
 *
 * <pre>{@code
 * class JsonCodec implements HttpCodec {
 *     public Object decode(FullHttpRequest request, Type type) throws IOException {
 *         return mapper.readValue(new ByteBufInputStream(request.content()), mapper.constructType(type));
 *     }
 *     public FullHttpResponse encode(Object result) throws IOException { ... }
 * }
 * }</pre>
 *
 * An endpoint taking a FullHttpRequest gets the request itself, and a FullHttpResponse result is sent as is: neither
 * goes through the codec, which only turns other objects into requests' arguments and responses. {@link #basic()}
 * reads and writes text and bytes. Decoding runs on the connection's EventLoop before the endpoint is called,
 * encoding on whichever thread answers; a codec must be thread-safe.
 */
public interface HttpCodec {
    /**
     * The argument for an endpoint whose parameter is of {@code type}, read from the request, normally from its
     * body; a GET endpoint may read the query instead. An exception answers 400.
     */
    Object decode(FullHttpRequest request, Type type) throws Exception;

    /**
     * The response for an endpoint's result, any but a FullHttpResponse. An exception answers with the failure for
     * RUNTIME_EXECUTION_ERROR.
     */
    FullHttpResponse encode(Object result) throws Exception;

    /**
     * The response for a failed request: 503 when the runtime refused the request because it is shut down or
     * full, 500 otherwise, with the error code as text.
     */
    default FullHttpResponse encodeFailure(int errorCode) {
        HttpResponseStatus status = errorCode == RUNTIME_CLOSED || errorCode == ROUTE_EXECUTOR_OVERLOADED
                || errorCode == ROUTE_EXECUTOR_CLOSED ? HttpResponseStatus.SERVICE_UNAVAILABLE : HttpResponseStatus.INTERNAL_SERVER_ERROR;
        return BasicHttpCodec.text(status, FrameworkErrorCodes.describe(errorCode));
    }

    /**
     * Reads a parameter of type String as the UTF-8 body and byte[] as the body's bytes. Writes text as UTF-8
     * text/plain, byte[] and ByteBuf as application/octet-stream. HttpRoutes using it reject endpoints taking other
     * types when built.
     */
    static HttpCodec basic() { return BasicHttpCodec.INSTANCE; }
}
