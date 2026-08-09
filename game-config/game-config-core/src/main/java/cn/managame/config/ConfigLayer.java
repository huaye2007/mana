package cn.managame.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One config source within a {@link ConfigOptions} stack.
 *
 * <p>A layer names the backend ({@link #type()}) plus everything that backend needs to reach its
 * documents. Layers declared later in {@link ConfigOptions#layers()} override earlier ones, so the
 * usual shape is file defaults first, remote overrides next, and process overrides last.</p>
 */
public final class ConfigLayer {
    /** Layer type reading process environment variables. */
    public static final String ENVIRONMENT = "env";
    /** Layer type reading JVM system properties. */
    public static final String SYSTEM_PROPERTIES = "system";
    /** Layer type reading in-memory values, intended for tests and embedded defaults. */
    public static final String MEMORY = "memory";

    private final String type;
    private final String name;
    private final String endpoint;
    private final List<String> resources;
    private final Map<String, String> properties;

    private ConfigLayer(Builder builder) {
        type = requireText(builder.type, "type").toLowerCase(Locale.ROOT);
        name = builder.name == null ? type : requireText(builder.name, "name");
        endpoint = builder.endpoint == null ? "" : builder.endpoint.trim();
        resources = List.copyOf(builder.resources);
        properties = Map.copyOf(builder.properties);
    }

    public static Builder builder(String type) { return new Builder(type); }

    /**
     * Environment variables mapped to config keys: {@code GAME_DB_URL} becomes {@code game.db.url}.
     *
     * <p>{@code prefix} filters which variables are read and is matched before mapping; it is not
     * stripped, so {@code GAME_} keeps the {@code game.} key namespace. Pass an empty prefix to read
     * every variable. Use {@code __} in a variable name for a literal underscore.</p>
     */
    public static ConfigLayer environment(String prefix) {
        return builder(ENVIRONMENT).property("prefix", prefix).build();
    }

    /** System properties whose names start with {@code prefix}, used as config keys unchanged. */
    public static ConfigLayer systemProperties(String prefix) {
        return builder(SYSTEM_PROPERTIES).property("prefix", prefix).build();
    }

    /** Fixed values, useful for defaults declared in code and for tests. */
    public static ConfigLayer inline(Map<String, String> values) {
        Builder builder = builder(MEMORY);
        values.forEach(builder::property);
        return builder.build();
    }

    /**
     * Values driven at runtime through
     * {@link cn.managame.config.source.MemoryConfigSource#named(String) MemoryConfigSource.named(name)},
     * which lets a test publish updates into a running center.
     */
    public static ConfigLayer memory(String name) {
        return builder(MEMORY).property("name", requireText(name, "name")).build();
    }

    public String type() { return type; }

    /**
     * Name used when reporting which layer a value came from. Defaults to {@link #type()}, and two
     * layers sharing a name are distinguished by position when the center reports origins.
     */
    public String name() { return name; }

    public String endpoint() { return endpoint; }
    public List<String> resources() { return resources; }
    public Map<String, String> properties() { return properties; }
    public String property(String key, String defaultValue) { return properties.getOrDefault(key, defaultValue); }

    /** Returns the declared resources, failing when the backend needs at least one and none was given. */
    public List<String> requireResources() {
        if (resources.isEmpty()) {
            throw new IllegalArgumentException(type + " config layer requires at least one resource");
        }
        return resources;
    }

    /** Returns the endpoint, failing when the backend cannot work without one. */
    public String requireEndpoint() {
        if (endpoint.isBlank()) throw new IllegalArgumentException(type + " config layer requires an endpoint");
        return endpoint;
    }

    /** Reads a positive long layer property, for example a timeout. */
    public long longProperty(String key, long defaultValue) {
        String value = property(key, null);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number: " + value, e);
        }
    }

    /** Reads a boolean layer property, accepting only {@code true} and {@code false}. */
    public boolean booleanProperty(String key, boolean defaultValue) {
        String value = property(key, null);
        if (value == null || value.isBlank()) return defaultValue;
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) return true;
        if (trimmed.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false: " + value);
    }

    @Override public String toString() {
        return "ConfigLayer[name=" + name + ", type=" + type + ", endpoint=" + endpoint
                + ", resources=" + resources + "]";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    public static final class Builder {
        private final String type;
        private String name;
        private String endpoint;
        private final List<String> resources = new ArrayList<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(String type) { this.type = type; }

        /** Names this layer for origin reporting; useful when a stack has two layers of one type. */
        public Builder name(String value) { name = value; return this; }

        public Builder endpoint(String value) { endpoint = value; return this; }
        public Builder resource(String value) { resources.add(requireText(value, "resource")); return this; }
        public Builder resources(Iterable<String> values) { values.forEach(this::resource); return this; }
        public Builder property(String key, String value) {
            properties.put(requireText(key, "property key"), Objects.requireNonNull(value, "property value"));
            return this;
        }
        public Builder properties(Map<String, String> values) { values.forEach(this::property); return this; }
        public ConfigLayer build() { return new ConfigLayer(this); }
    }
}
