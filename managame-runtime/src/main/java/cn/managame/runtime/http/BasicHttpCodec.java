package cn.managame.runtime.http;

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

    static boolean decodes(Type type) { return type == String.class || type == byte[].class || type == FullHttpRequest.class; }

    @Override public Object decode(FullHttpRequest request, Type type) {
        if (type == String.class) return request.content().toString(StandardCharsets.UTF_8);
        if (type == byte[].class) return ByteBufUtil.getBytes(request.content());
        if (type == FullHttpRequest.class) return request;
        throw new IllegalArgumentException("No HTTP decoding for " + type.getTypeName() + "; configure an HttpCodec");
    }

    @Override public FullHttpResponse encode(Object result) {
        return switch (result) {
            case FullHttpResponse response -> response;
            case CharSequence text -> text(HttpResponseStatus.OK, text.toString());
            case byte[] bytes -> bytes(Unpooled.wrappedBuffer(bytes));
            case ByteBuf bytes -> bytes(bytes);
            default -> throw new IllegalArgumentException("No HTTP encoding for " + result.getClass().getName()
                    + "; configure an HttpCodec");
        };
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
