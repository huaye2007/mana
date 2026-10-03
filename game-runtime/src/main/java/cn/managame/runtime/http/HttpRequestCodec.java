package cn.managame.runtime.http;

import io.netty.handler.codec.http.FullHttpRequest;
import java.lang.reflect.Type;
import java.util.function.Function;

/** Compiles a thread-safe decoder once at build. Decoded values must not retain borrowed requests. */
@FunctionalInterface
public interface HttpRequestCodec {
    Function<FullHttpRequest, Object> decoder(Type type);
    static HttpRequestCodec json() { return JsonHttpRequestCodec.INSTANCE; }
}
