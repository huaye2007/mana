package cn.managame.runtime.http;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_CLOSED;
import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.SUCCESS;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/** {@link HttpCodec#basic()}. */
final class BasicHttpCodec implements HttpCodec {
    static final BasicHttpCodec INSTANCE = new BasicHttpCodec();

    private BasicHttpCodec() {}

    static boolean decodes(Type type) { return type == String.class || type == byte[].class; }

    @Override public Object decode(FullHttpRequest request, Type type) {
        if (type == String.class) return request.content().toString(StandardCharsets.UTF_8);
        if (type == byte[].class) return ByteBufUtil.getBytes(request.content());
        throw new IllegalArgumentException("No HTTP decoding for " + type.getTypeName() + "; configure an HttpCodec");
    }

    @Override public FullHttpResponse encode(Object result) {
        return switch (result) {
            case HttpResult failed when failed.code() != SUCCESS -> failure(failed.code(), failed.msg());
            case HttpResult empty when empty.data() == null -> text(HttpResponseStatus.OK, empty.msg());
            case HttpResult success -> encode(success.data());
            case CharSequence text -> text(HttpResponseStatus.OK, text.toString());
            case byte[] bytes -> bytes(Unpooled.wrappedBuffer(bytes));
            case ByteBuf bytes -> bytes(bytes);
            default -> throw new IllegalArgumentException("No HTTP encoding for " + result.getClass().getName()
                    + "; configure an HttpCodec");
        };
    }

    /** 503 when the runtime refused the request because it is shut down or full, 500 otherwise, with {@code text}. */
    static FullHttpResponse failure(int errorCode, String text) {
        boolean refused = errorCode == RUNTIME_CLOSED || errorCode == ROUTE_EXECUTOR_OVERLOADED || errorCode == ROUTE_EXECUTOR_CLOSED;
        return text(refused ? HttpResponseStatus.SERVICE_UNAVAILABLE : HttpResponseStatus.INTERNAL_SERVER_ERROR, text);
    }

    static FullHttpResponse text(HttpResponseStatus status, String text) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        return response;
    }

    private static FullHttpResponse bytes(ByteBuf content) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        return response;
    }
}
