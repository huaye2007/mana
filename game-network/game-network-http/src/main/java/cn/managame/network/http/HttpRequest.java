package cn.managame.network.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record HttpRequest(
        String method,
        String uri,
        Map<String, List<String>> headers,
        byte[] body,
        String remoteAddress
) {
    public HttpRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        method = method.toUpperCase(Locale.ROOT);
        uri = Objects.requireNonNull(uri, "uri");
        headers = immutableHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
        remoteAddress = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public Optional<String> header(String name) {
        List<String> values = headers.get(normalizeHeaderName(name));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    public List<String> headerValues(String name) {
        return headers.getOrDefault(normalizeHeaderName(name), List.of());
    }

    static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String normalized = normalizeHeaderName(name);
            List<String> safeValues = values == null ? List.of() : new ArrayList<>(values);
            copy.merge(normalized, List.copyOf(safeValues), (left, right) -> {
                List<String> merged = new ArrayList<>(left.size() + right.size());
                merged.addAll(left);
                merged.addAll(right);
                return List.copyOf(merged);
            });
        });
        return Map.copyOf(copy);
    }

    static String normalizeHeaderName(String name) {
        Objects.requireNonNull(name, "header name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("header name must not be blank");
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
