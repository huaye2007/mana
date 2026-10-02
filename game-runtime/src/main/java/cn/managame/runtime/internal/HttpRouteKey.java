package cn.managame.runtime.internal;

import com.fasterxml.jackson.core.*;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.io.IOException;

/** Compiled ingress extraction only; does not execute business state access or change buffer ownership. */
@FunctionalInterface
interface HttpRouteKey {
    JsonFactory JSON = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonFactory.Feature.CHARSET_DETECTION).build();

    long extract(FullHttpRequest request) throws Throwable;

    static HttpRouteKey field(String field) {
        return request -> {
            if (request.method().name().equals("GET")) {
                var values = new QueryStringDecoder(request.uri(), java.nio.charset.StandardCharsets.UTF_8,
                        true, Integer.MAX_VALUE).parameters().get(field);
                if (values == null || values.size() != 1) throw invalid();
                return decimal(values.getFirst());
            }
            // duplicate() keeps the original reader/writer indices and borrowed reference untouched.
            try (var parser = JSON.createParser((java.io.InputStream) new ByteBufInputStream(request.content().duplicate(), false))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
                Long key = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid();
                    String name = parser.currentName();
                    JsonToken token = parser.nextToken();
                    if (token == null) throw invalid();
                    if (field.equals(name)) {
                        if (token == JsonToken.VALUE_NUMBER_INT) key = parser.getLongValue();
                        else if (token == JsonToken.VALUE_STRING) key = decimal(parser.getText());
                        else throw invalid();
                    } else parser.skipChildren();
                }
                if (key == null || parser.nextToken() != null) throw invalid();
                return key;
            } catch (IOException cause) { throw new IllegalArgumentException("Invalid HTTP RouteKey JSON", cause); }
        };
    }

    private static long decimal(String value) {
        int start = value.startsWith("-") ? 1 : 0;
        if (value.length() == start || value.length() > 20) throw invalid();
        for (int i = start; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') throw invalid();
        return Long.parseLong(value);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid HTTP RouteKey field"); }
}
