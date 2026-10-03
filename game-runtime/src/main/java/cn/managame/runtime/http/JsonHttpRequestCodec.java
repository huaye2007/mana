package cn.managame.runtime.http;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import java.lang.reflect.Type;
import java.io.IOException;
import java.util.function.Function;

final class JsonHttpRequestCodec implements HttpRequestCodec {
    static final JsonHttpRequestCodec INSTANCE = new JsonHttpRequestCodec();
    private final ObjectMapper mapper = new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public Function<FullHttpRequest, Object> decoder(Type type) {
        if (type == String.class) return request -> request.content().toString(CharsetUtil.UTF_8);
        ObjectReader reader = mapper.readerFor(mapper.constructType(type));
        return request -> {
            try {
                Object value;
                if (request.method().equals(io.netty.handler.codec.http.HttpMethod.GET)) {
                    var node = mapper.createObjectNode();
                    new QueryStringDecoder(request.uri()).parameters().forEach((name, values) -> {
                        if (values.size() == 1) node.put(name, values.getFirst());
                        else { var array = node.putArray(name); values.forEach(array::add); }
                    });
                    value = reader.readValue(node);
                } else value = reader.readValue(request.content().toString(CharsetUtil.UTF_8));
                if (value == null) throw new IllegalArgumentException("HTTP payload cannot be null");
                return value;
            } catch (IOException error) { throw new IllegalArgumentException("Invalid HTTP payload for " + type.getTypeName(), error); }
        };
    }
}
