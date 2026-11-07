package cn.managame.runtime.http;

import cn.managame.FrameworkErrorCodes;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
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
 * goes through the codec, which only turns other objects into requests' arguments and responses, an
 * {@link HttpResult} and an endpoint's own type alike. {@link #basic()} reads and writes text and bytes. Decoding runs
 * on the connection's EventLoop before the endpoint is called, encoding on whichever thread answers; a codec must be
 * thread-safe.
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
     * The response for a failed request, whatever its endpoint returns when it succeeds: the runtime's refusal, an
     * exception and {@code fail} on the context all come here. {@code args} are the failure's, an empty array when it
     * gave none. HTTP shows a text rather than args, so a codec makes it from the application's own messages:
     *
     * <pre>{@code
     * public FullHttpResponse encodeFailure(int errorCode, String[] args) throws IOException {
     *     return encode(HttpResult.fail(errorCode, messages.format(errorCode, args)));
     * }
     * }</pre>
     *
     * By default 503 when the runtime refused the request because it is shut down or full, 500 otherwise, with the
     * error code as text, leaving the args out. An exception answers 500 with no body.
     */
    default FullHttpResponse encodeFailure(int errorCode, String[] args) throws Exception {
        return BasicHttpCodec.failure(errorCode, FrameworkErrorCodes.describe(errorCode));
    }

    /**
     * Reads a parameter of type String as the UTF-8 body and byte[] as the body's bytes. Writes text as UTF-8
     * text/plain, byte[] and ByteBuf as application/octet-stream, and an {@link HttpResult} as its data, or, when it
     * failed, with the statuses of {@link #encodeFailure} and its msg as text. HttpRoutes using it reject endpoints
     * taking other types when built.
     */
    static HttpCodec basic() { return BasicHttpCodec.INSTANCE; }
}
