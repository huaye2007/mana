package cn.managame.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

public record ConfigSnapshot(long version, Instant loadedAt, Map<String, String> values) {
    public ConfigSnapshot {
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        loadedAt = java.util.Objects.requireNonNull(loadedAt, "loadedAt");
        values = Map.copyOf(values);
    }

    public String get(String key) {
        return values.get(key);
    }

    public String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public Optional<String> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public String require(String key) {
        String value = values.get(key);
        if (value == null) throw new NoSuchElementException("missing config: " + key);
        return value;
    }

    public int getInt(String key, int defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : Integer.parseInt(value.trim());
    }

    public long getLong(String key, long defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : Long.parseLong(value.trim());
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("config " + key + " is not a boolean: " + value);
        };
    }

    public Duration getDuration(String key, Duration defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : Duration.parse(value.trim());
    }
}
