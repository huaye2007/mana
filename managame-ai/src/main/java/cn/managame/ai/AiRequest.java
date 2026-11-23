package cn.managame.ai;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An outbound request sent through {@link AiClient}. Immutable: {@link #withHeader}, {@link #withHeaders} and
 * {@link #withTimeout} return derived requests without changing this one.
 */
public final class AiRequest {
    private final String path;
    private final Object body;
    private final Map<String, String> headers;
    private final Duration timeout;

    private AiRequest(String path, Object body, Map<String, String> headers, Duration timeout) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path must not be blank");
        if (timeout != null && timeout.toMillis() < 1) {
            throw new IllegalArgumentException("timeout must be at least one millisecond");
        }
        this.path = path;
        this.body = body;
        this.headers = headers;
        this.timeout = timeout;
    }

    /** Creates a request to {@code path} with {@code body} (nullable for an empty body). */
    public static AiRequest of(String path, Object body) {
        return new AiRequest(path, body, Map.of(), null);
    }

    /** Creates a request to {@code path} with no body. */
    public static AiRequest of(String path) {
        return new AiRequest(path, null, Map.of(), null);
    }

    public String path() { return path; }
    public Object body() { return body; }
    public Map<String, String> headers() { return headers; }
    /** Per-request timeout, or {@code null} to use the {@link AiClient} default. */
    public Duration timeout() { return timeout; }

    /** Returns a request with {@code name} set to {@code value}, replacing any previous value for {@code name}. */
    public AiRequest withHeader(String name, String value) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("header name must not be blank");
        Objects.requireNonNull(value, "header value");
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new AiRequest(path, body, Collections.unmodifiableMap(copy), timeout);
    }

    /** Returns a request with all entries of {@code extra} merged into its headers. */
    public AiRequest withHeaders(Map<String, String> extra) {
        Objects.requireNonNull(extra, "extra");
        if (extra.isEmpty()) return this;
        Map<String, String> copy = new LinkedHashMap<>(headers);
        extra.forEach((k, v) -> {
            if (k == null || k.isBlank()) throw new IllegalArgumentException("header name must not be blank");
            Objects.requireNonNull(v, "header value");
            copy.put(k, v);
        });
        return new AiRequest(path, body, Collections.unmodifiableMap(copy), timeout);
    }

    /** Returns a request with a per-call timeout overriding the client default. */
    public AiRequest withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return new AiRequest(path, body, headers, timeout);
    }
}
