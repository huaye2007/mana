package cn.managame.config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable view of the merged config at one point in time.
 *
 * <p>Reads are lock-free map lookups. Typed accessors parse on every call, which is cheap but not
 * free; a value read on a hot path should be held in a {@link ConfigRef} so it is parsed once per
 * snapshot rather than once per read.</p>
 */
public record ConfigSnapshot(long version, Instant loadedAt, Map<String, String> values) {
    public ConfigSnapshot {
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        loadedAt = Objects.requireNonNull(loadedAt, "loadedAt");
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

    /** Returns the value, failing when the key is absent. */
    public String require(String key) {
        String value = values.get(key);
        if (value == null) throw new ConfigException("missing config: " + key);
        return value;
    }

    public int getInt(String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("config " + key + " is not an int: " + value, e);
        }
    }

    public long getLong(String key, long defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("config " + key + " is not a long: " + value, e);
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("config " + key + " is not a double: " + value, e);
        }
    }

    public float getFloat(String key, float defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("config " + key + " is not a float: " + value, e);
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new ConfigException("config " + key + " is not a boolean: " + value);
        };
    }

    /** Reads an ISO-8601 duration such as {@code PT30S}. */
    public Duration getDuration(String key, Duration defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        try {
            return Duration.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ConfigException("config " + key + " is not an ISO-8601 duration: " + value, e);
        }
    }

    /** Reads an enum constant, matching case-insensitively. */
    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E defaultValue) {
        String value = values.get(key);
        if (value == null) return defaultValue;
        String trimmed = value.trim();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(trimmed)) return constant;
        }
        throw new ConfigException("config " + key + " is not a " + type.getSimpleName() + ": " + value);
    }

    /**
     * Reads a list value.
     *
     * <p>Indexed keys win when present, so a JSON document flattened to {@code servers[0]},
     * {@code servers[1]} reads back as a list under {@code servers}. Otherwise the raw value is split
     * on commas, with blank elements dropped. An absent key yields an empty list.</p>
     */
    public List<String> getList(String key) {
        List<String> indexed = indexedValues(key);
        if (indexed != null) return indexed;
        String value = values.get(key);
        if (value == null || value.isBlank()) return List.of();
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return List.copyOf(parts);
    }

    /** Reads a list of ints, applying the parsing rules of {@link #getList(String)}. */
    public List<Integer> getIntList(String key) {
        List<String> raw = getList(key);
        List<Integer> parsed = new ArrayList<>(raw.size());
        for (String element : raw) {
            try {
                parsed.add(Integer.valueOf(element.trim()));
            } catch (NumberFormatException e) {
                throw new ConfigException("config " + key + " has a non-int element: " + element, e);
            }
        }
        return List.copyOf(parsed);
    }

    /** Returns keys of {@code prefix}-scoped entries with the prefix removed, for sub-tree reads. */
    public Map<String, String> subMap(String prefix) {
        String scope = prefix.endsWith(".") ? prefix : prefix + ".";
        Map<String, String> scoped = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.length() > scope.length() && key.startsWith(scope)) {
                scoped.put(key.substring(scope.length()), value);
            }
        });
        return Map.copyOf(scoped);
    }

    /** Collects {@code key[0]}, {@code key[1]}, ... or returns {@code null} when the key is not indexed. */
    private List<String> indexedValues(String key) {
        if (!values.containsKey(key + "[0]")) return null;
        List<String> elements = new ArrayList<>();
        for (int index = 0; ; index++) {
            String element = values.get(key + "[" + index + "]");
            if (element == null) return List.copyOf(elements);
            elements.add(element);
        }
    }
}
