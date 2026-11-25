package cn.managame.ai;

import cn.managame.Metadata;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable outbound request to an external AI service. Carries the HTTP {@link #method} ({@code POST} by default),
 * relative endpoint {@link #path}, request {@link #body} (a raw JSON {@link String}, {@code byte[]}, a DTO encoded by
 * the client's {@link AiCodec}, or {@code null}), optional per-request headers, optional {@link Metadata} overrides,
 * optional explicit framework {@link #context()} (for entries outside an active route task), and optional total /
 * stream-idle timeout overrides.
 */
public final class AiRequest {
    private final String method;
    private final String path;
    private final Object body;
    private final Map<String, String> headers;
    private final Metadata metadata;
    private final Object context;
    private final Duration timeout;
    private final Duration idleTimeout;

    private AiRequest(String method, String path, Object body, Map<String, String> headers,
            Metadata metadata, Object context, Duration timeout, Duration idleTimeout) {
        this.method = method;
        this.path = Objects.requireNonNull(path, "path");
        this.body = body;
        this.headers = headers;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.context = context;
        this.timeout = timeout;
        this.idleTimeout = idleTimeout;
    }

    /** Creates a POST request to {@code path} (relative to {@link AiClient}'s base URI) with {@code body}. */
    public static AiRequest of(String path, Object body) {
        return new AiRequest("POST", path, body, Map.of(), Metadata.empty(), null, null, null);
    }

    /** Creates a POST request carrying additional {@code metadata} to propagate as HTTP headers. */
    public static AiRequest of(String path, Object body, Metadata metadata) {
        return new AiRequest("POST", path, body, Map.of(), metadata, null, null, null);
    }

    /** Creates a GET request to {@code path} with no body (for polling async jobs or health checks). */
    public static AiRequest get(String path) {
        return new AiRequest("GET", path, null, Map.of(), Metadata.empty(), null, null, null);
    }

    /** Creates a GET request carrying additional {@code metadata}. */
    public static AiRequest get(String path, Metadata metadata) {
        return new AiRequest("GET", path, null, Map.of(), metadata, null, null, null);
    }

    /** Creates a DELETE request to {@code path} with no body (for clearing remote sessions or cancelling jobs). */
    public static AiRequest delete(String path) {
        return new AiRequest("DELETE", path, null, Map.of(), Metadata.empty(), null, null, null);
    }

    /** Creates a DELETE request carrying additional {@code metadata}. */
    public static AiRequest delete(String path, Metadata metadata) {
        return new AiRequest("DELETE", path, null, Map.of(), metadata, null, null, null);
    }

    /** Percent-encodes a single URL path segment or query token using UTF-8 (encoding spaces as {@code %20}). */
    public static String encodeSegment(String segment) {
        Objects.requireNonNull(segment, "segment");
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public String method() { return method; }

    public String path() { return path; }

    public Object body() { return body; }

    public Map<String, String> headers() { return headers; }

    public Metadata metadata() { return metadata; }

    /**
     * Explicit framework execution context passed to {@link cn.managame.ai.spi.AiDispatcher#capture(Object)}, or
     * {@code null} to capture the calling thread's ambient context.
     */
    public Object context() { return context; }

    /** Per-request total timeout override, or {@code null} to use the client's default. */
    public Duration timeout() { return timeout; }

    /** Per-stream idle timeout override (max gap between chunks), or {@code null} to use the client's default. */
    public Duration idleTimeout() { return idleTimeout; }

    /** Returns a copy using HTTP {@code method} (for example {@code GET}, {@code POST}, {@code PUT}, {@code DELETE}). */
    public AiRequest withMethod(String method) {
        Objects.requireNonNull(method, "method");
        if (method.isBlank() || method.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid HTTP method: '" + method + "'");
        }
        return new AiRequest(method.toUpperCase(Locale.ROOT), path, body, headers, metadata, context, timeout, idleTimeout);
    }

    /** Returns a copy with URL-encoded query parameter {@code name=value} appended to {@link #path()}. */
    public AiRequest withQuery(String name, Object value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isBlank()) throw new IllegalArgumentException("Query parameter name must not be blank");
        String sep = path.indexOf('?') >= 0 ? "&" : "?";
        String updatedPath = path + sep + encodeSegment(name) + "=" + encodeSegment(value.toString());
        return new AiRequest(method, updatedPath, body, headers, metadata, context, timeout, idleTimeout);
    }

    /** Returns a copy with header {@code name} set to {@code value}. */
    public AiRequest withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isBlank()) throw new IllegalArgumentException("Header name must not be blank");
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new AiRequest(method, path, body, Map.copyOf(copy), metadata, context, timeout, idleTimeout);
    }

    /** Returns a copy carrying {@code metadata}. */
    public AiRequest withMetadata(Metadata metadata) {
        return new AiRequest(method, path, body, headers, metadata, context, timeout, idleTimeout);
    }

    /**
     * Returns a copy bound to an explicit framework {@code context} (for example a {@code GameContext} when initiating
     * a call from an external entry outside an active route task).
     */
    public AiRequest withContext(Object context) {
        Objects.requireNonNull(context, "context");
        return new AiRequest(method, path, body, headers, metadata, context, timeout, idleTimeout);
    }

    /** Returns a copy with an explicit positive total {@code timeout}. */
    public AiRequest withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        return new AiRequest(method, path, body, headers, metadata, context, timeout, idleTimeout);
    }

    /** Returns a copy with an explicit positive SSE {@code idleTimeout} (time to first chunk and gap between chunks). */
    public AiRequest withIdleTimeout(Duration idleTimeout) {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        return new AiRequest(method, path, body, headers, metadata, context, timeout, idleTimeout);
    }
}
