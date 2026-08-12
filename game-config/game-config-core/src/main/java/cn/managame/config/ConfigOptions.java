package cn.managame.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything {@link ConfigFactory#open(ConfigOptions)} needs: which backend to read, what to read
 * from it, and the validation and refresh policy.
 *
 * {@snippet :
 * ConfigOptions.builder("nacos")
 *         .endpoint("127.0.0.1:8848")
 *         .resource("GAME:application.yml")
 *         .require("game.db.password")
 *         .build();
 * }
 */
public final class ConfigOptions {
    private final String type;
    private final String endpoint;
    private final List<String> resources;
    private final Map<String, String> properties;
    private final ConfigValidator validator;
    private final Duration healthCheckInterval;
    private final Duration refreshInterval;
    private final Duration staleAfter;

    private ConfigOptions(Builder builder) {
        type = requireText(builder.type, "type").toLowerCase(Locale.ROOT);
        endpoint = builder.endpoint == null ? "" : builder.endpoint.trim();
        resources = List.copyOf(builder.resources);
        properties = Map.copyOf(builder.properties);
        healthCheckInterval = requireNonNegative(builder.healthCheckInterval, "healthCheckInterval");
        refreshInterval = requireNonNegative(builder.refreshInterval, "refreshInterval");
        staleAfter = requireNonNegative(builder.staleAfter, "staleAfter");
        validator = builder.requiredKeys.isEmpty() ? builder.validator
                : ConfigValidator.requireKeys(builder.requiredKeys).and(builder.validator);
    }

    public static Builder builder(String type) { return new Builder(type); }

    public String type() { return type; }
    public String endpoint() { return endpoint; }
    public List<String> resources() { return resources; }
    public Map<String, String> properties() { return properties; }
    public ConfigValidator validator() { return validator; }
    public String property(String key, String defaultValue) { return properties.getOrDefault(key, defaultValue); }

    /** How often to cheaply probe the backend for liveness. {@link Duration#ZERO} disables probing. */
    public Duration healthCheckInterval() { return healthCheckInterval; }

    /**
     * How often to reload in full, as a safety net for an update the watch may have missed.
     * {@link Duration#ZERO} disables the periodic reload.
     */
    public Duration refreshInterval() { return refreshInterval; }

    /** How long the backend may go without a successful contact before the center reports unhealthy. */
    public Duration staleAfter() { return staleAfter; }

    /** Returns the declared resources, failing when the backend needs at least one and none was given. */
    public List<String> requireResources() {
        if (resources.isEmpty()) throw new IllegalArgumentException(type + " config requires at least one resource");
        return resources;
    }

    /** Returns the endpoint, failing when the backend cannot work without one. */
    public String requireEndpoint() {
        if (endpoint.isBlank()) throw new IllegalArgumentException(type + " config requires an endpoint");
        return endpoint;
    }

    /** Reads a numeric provider property, for example a timeout. */
    public long longProperty(String key, long defaultValue) {
        String value = property(key, null);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number: " + value, e);
        }
    }

    /** Reads a boolean provider property, accepting only {@code true} and {@code false}. */
    public boolean booleanProperty(String key, boolean defaultValue) {
        String value = property(key, null);
        if (value == null || value.isBlank()) return defaultValue;
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) return true;
        if (trimmed.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false: " + value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    public static final class Builder {
        private final String type;
        private String endpoint;
        private final List<String> resources = new ArrayList<>();
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Set<String> requiredKeys = new LinkedHashSet<>();
        private ConfigValidator validator = ConfigValidator.none();
        private Duration healthCheckInterval = Duration.ofSeconds(30);
        private Duration refreshInterval = Duration.ofMinutes(5);
        private Duration staleAfter = Duration.ofSeconds(90);

        private Builder(String type) { this.type = type; }

        public Builder endpoint(String value) { endpoint = value; return this; }
        public Builder resource(String value) { resources.add(requireText(value, "resource")); return this; }
        public Builder resources(Iterable<String> values) { values.forEach(this::resource); return this; }
        public Builder property(String key, String value) {
            properties.put(requireText(key, "property key"), Objects.requireNonNull(value, "property value"));
            return this;
        }
        public Builder properties(Map<String, String> values) { values.forEach(this::property); return this; }

        /** Keys that must be present and non-blank in every snapshot, including the first one. */
        public Builder require(String... keys) { return require(List.of(keys)); }

        /** Keys that must be present and non-blank in every snapshot, including the first one. */
        public Builder require(Iterable<String> keys) {
            keys.forEach(key -> requiredKeys.add(Objects.requireNonNull(key, "required key")));
            return this;
        }

        public Builder validator(ConfigValidator value) {
            validator = Objects.requireNonNull(value, "validator");
            return this;
        }

        public Builder healthCheckInterval(Duration value) {
            healthCheckInterval = Objects.requireNonNull(value, "healthCheckInterval");
            return this;
        }

        public Builder refreshInterval(Duration value) {
            refreshInterval = Objects.requireNonNull(value, "refreshInterval");
            return this;
        }

        public Builder staleAfter(Duration value) {
            staleAfter = Objects.requireNonNull(value, "staleAfter");
            return this;
        }

        public ConfigOptions build() { return new ConfigOptions(this); }
    }
}
