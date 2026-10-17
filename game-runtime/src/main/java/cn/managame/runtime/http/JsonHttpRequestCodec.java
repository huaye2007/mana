package cn.managame.runtime.http;

import com.fasterxml.jackson.databind.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.buffer.ByteBufInputStream;
import io.netty.util.CharsetUtil;
import java.lang.reflect.Type;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

final class JsonHttpRequestCodec implements HttpRequestCodec {
    static final JsonHttpRequestCodec INSTANCE = new JsonHttpRequestCodec();
    private final ObjectMapper mapper = JsonSupport.MAPPER;
    public Function<FullHttpRequest, Object> decoder(Type type) {
        if (type == String.class) return request -> request.content().toString(CharsetUtil.UTF_8);
        ObjectReader reader = mapper.readerFor(mapper.constructType(type));
        return request -> {
            try {
                Object value;
                // Netty's method type; this package's HttpMethod is the endpoint annotation.
                if (request.method().equals(io.netty.handler.codec.http.HttpMethod.GET)) {
                    var node = mapper.createObjectNode();
                    new QueryStringDecoder(request.uri()).parameters().forEach((name, values) -> {
                        if (values.size() == 1) node.put(name, values.getFirst());
                        else { var array = node.putArray(name); values.forEach(array::add); }
                    });
                    value = reader.readValue(node);
                } else {
                    // Parse the borrowed content in place; no intermediate String copy.
                    try (InputStream in = new ByteBufInputStream(request.content().duplicate(), false)) {
                        value = reader.readValue(in);
                    }
                }
                if (value == null) throw new IllegalArgumentException("HTTP payload cannot be null");
                return value;
            } catch (IOException error) { throw new IllegalArgumentException("Invalid HTTP payload for " + type.getTypeName(), error); }
        };
    }
}
