package cn.managame.network.http;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HttpResponse(
        int status,
        Map<String, List<String>> headers,
        byte[] body
) {
    public HttpResponse {
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("status must be between 200 and 599");
        }
        headers = HttpRequest.immutableHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public static HttpResponse of(int status, byte[] body) {
        return new HttpResponse(status, Map.of(), body);
    }

    public static HttpResponse ok(byte[] body) {
        return of(200, body);
    }

    public static HttpResponse empty(int status) {
        return of(status, new byte[0]);
    }

    public static HttpResponse text(int status, String body, Charset charset) {
        Objects.requireNonNull(charset, "charset");
        return new HttpResponse(status,
                Map.of("content-type", List.of("text/plain; charset=" + charset.name())),
                Objects.requireNonNullElse(body, "").getBytes(charset));
    }
}
