package cn.managame.registry.factory;

import java.util.Map;

public final class RegistryConfig {
    private final String type;
    private final String endpoints;
    private final Map<String, String> properties;

    private RegistryConfig(Builder builder) {
        type = requireText(builder.type, "type").toLowerCase(java.util.Locale.ROOT);
        endpoints = requireText(builder.endpoints, "endpoints");
        properties = Map.copyOf(builder.properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() { return type; }
    public String getEndpoints() { return endpoints; }
    public Map<String, String> getProperties() { return properties; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    public static final class Builder {
        private String type;
        private String endpoints;
        private Map<String, String> properties = Map.of();

        private Builder() {
        }

        public Builder type(String value) { type = value; return this; }
        public Builder type(RegistryType value) { type = value == null ? null : value.type(); return this; }
        public Builder endpoints(String value) { endpoints = value; return this; }
        public Builder properties(Map<String, String> value) { properties = value == null ? Map.of() : Map.copyOf(value); return this; }
        public RegistryConfig build() { return new RegistryConfig(this); }
    }
}
