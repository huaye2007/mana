package cn.managame.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigOptions {
    private final String type;
    private final String endpoint;
    private final List<String> resources;
    private final Map<String, String> properties;

    private ConfigOptions(Builder builder) {
        type = requireText(builder.type, "type").toLowerCase(java.util.Locale.ROOT);
        endpoint = builder.endpoint == null ? "" : builder.endpoint.trim();
        resources = List.copyOf(builder.resources);
        if (resources.isEmpty()) throw new IllegalArgumentException("at least one resource is required");
        properties = Map.copyOf(builder.properties);
    }

    public static Builder builder(String type) { return new Builder(type); }
    public String type() { return type; }
    public String endpoint() { return endpoint; }
    public List<String> resources() { return resources; }
    public Map<String, String> properties() { return properties; }
    public String property(String key, String defaultValue) { return properties.getOrDefault(key, defaultValue); }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    public static final class Builder {
        private final String type;
        private String endpoint;
        private final List<String> resources = new ArrayList<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(String type) { this.type = type; }
        public Builder endpoint(String value) { endpoint = value; return this; }
        public Builder resource(String value) { resources.add(requireText(value, "resource")); return this; }
        public Builder resources(Iterable<String> values) { values.forEach(this::resource); return this; }
        public Builder property(String key, String value) {
            properties.put(requireText(key, "property key"), java.util.Objects.requireNonNull(value, "property value"));
            return this;
        }
        public Builder properties(Map<String, String> values) { values.forEach(this::property); return this; }
        public ConfigOptions build() { return new ConfigOptions(this); }
    }
}
