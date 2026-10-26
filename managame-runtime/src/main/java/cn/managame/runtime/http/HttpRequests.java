package cn.managame.runtime.http;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reading HTTP requests in source functions and handlers. */
public final class HttpRequests {
    private HttpRequests() {}

    /** The first value of a query parameter, decoded; null when absent. */
    public static String query(FullHttpRequest request, String name) {
        List<String> values = new QueryStringDecoder(request.uri()).parameters().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /**
     * A query parameter as a long. IllegalArgumentException when it is absent or not a number, which a source
     * function lets HttpRoutes answer with 400.
     */
    public static long longQuery(FullHttpRequest request, String name) {
        String value = query(request, name);
        if (value == null) throw new IllegalArgumentException("Missing query parameter " + name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Query parameter " + name + " is not a number: " + value, e);
        }
    }

    /** The body as UTF-8 text. */
    public static String text(FullHttpRequest request) {
        return request.content().toString(StandardCharsets.UTF_8);
    }
}
