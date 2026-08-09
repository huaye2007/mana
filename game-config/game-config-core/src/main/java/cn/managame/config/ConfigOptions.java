package cn.managame.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything {@link ConfigFactory#open(ConfigOptions)} needs: the ordered {@linkplain ConfigLayer
 * layers} to merge, plus validation and refresh policy.
 *
 * <p>Single-backend use keeps the original shape:</p>
 * {@snippet :
 * ConfigOptions.builder("local").resource("config/application.properties").build();
 * }
 *
 * <p>Layered use stacks backends, later layers overriding earlier ones:</p>
 * {@snippet :
 * ConfigOptions.builder()
 *         .layer(ConfigLayer.builder("local").resource("config/base.properties").build())
 *         .layer(ConfigLayer.builder("nacos").endpoint("127.0.0.1:8848").resource("GAME:app.properties").build())
 *         .layer(ConfigLayer.systemProperties("game."))
 *         .layer(ConfigLayer.environment("GAME_"))
 *         .require("game.db.password")
 *         .build();
 * }
 */
public final class ConfigOptions {
    private final List<ConfigLayer> layers;
    private final ConfigValidator validator;
    private final Duration healthCheckInterval;
    private final Duration refreshInterval;
    private final Duration staleAfter;

    private ConfigOptions(Builder builder, List<ConfigLayer> effectiveLayers) {
        layers = List.copyOf(effectiveLayers);
        healthCheckInterval = requireNonNegative(builder.healthCheckInterval, "healthCheckInterval");
        refreshInterval = requireNonNegative(builder.refreshInterval, "refreshInterval");
        staleAfter = requireNonNegative(builder.staleAfter, "staleAfter");
        validator = builder.requiredKeys.isEmpty() ? builder.validator
                : ConfigValidator.requireKeys(builder.requiredKeys).and(builder.validator);
    }

    /** Starts a layered stack. Add at least one {@link ConfigLayer} before building. */
    public static Builder builder() { return new Builder(null); }

    /** Starts a single-layer stack of the given backend type, configured directly on this builder. */
    public static Builder builder(String type) { return new Builder(type); }

    /** The layers to merge, in declaration order; later layers override earlier ones. */
    public List<ConfigLayer> layers() { return layers; }

    public ConfigValidator validator() { return validator; }

    /** How often to cheaply probe every source for liveness. {@link Duration#ZERO} disables probing. */
    public Duration healthCheckInterval() { return healthCheckInterval; }

    /**
     * How often to reload every source in full, as a safety net for updates a watch may have missed.
     * {@link Duration#ZERO} disables the periodic reload.
     */
    public Duration refreshInterval() { return refreshInterval; }

    /** How long a source may go without a successful contact before the center reports unhealthy. */
    public Duration staleAfter() { return staleAfter; }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    public static final class Builder {
        private final List<ConfigLayer> layers = new ArrayList<>();
        private final Set<String> requiredKeys = new LinkedHashSet<>();
        private final ConfigLayer.Builder single;
        private ConfigValidator validator = ConfigValidator.none();
        private Duration healthCheckInterval = Duration.ofSeconds(30);
        private Duration refreshInterval = Duration.ofMinutes(5);
        private Duration staleAfter = Duration.ofSeconds(90);

        private Builder(String type) { single = type == null ? null : ConfigLayer.builder(type); }

        /** Appends a layer. Layers added later override values from layers added earlier. */
        public Builder layer(ConfigLayer value) {
            if (single != null) {
                throw new IllegalStateException("builder(type) configures a single layer; use builder() to stack layers");
            }
            layers.add(Objects.requireNonNull(value, "layer"));
            return this;
        }

        /** Appends the process environment as the highest-priority layer so far. */
        public Builder environment(String prefix) { return layer(ConfigLayer.environment(prefix)); }

        /** Appends JVM system properties as the highest-priority layer so far. */
        public Builder systemProperties(String prefix) { return layer(ConfigLayer.systemProperties(prefix)); }

        public Builder endpoint(String value) { requireSingle().endpoint(value); return this; }
        public Builder resource(String value) { requireSingle().resource(value); return this; }
        public Builder resources(Iterable<String> values) { requireSingle().resources(values); return this; }
        public Builder property(String key, String value) { requireSingle().property(key, value); return this; }
        public Builder properties(Map<String, String> values) { requireSingle().properties(values); return this; }

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

        public ConfigOptions build() {
            List<ConfigLayer> effective = new ArrayList<>(layers);
            if (single != null) effective.add(single.build());
            return new ConfigOptions(this, effective);
        }

        private ConfigLayer.Builder requireSingle() {
            if (single == null) {
                throw new IllegalStateException("builder() stacks layers; configure resources on each ConfigLayer");
            }
            return single;
        }
    }
}
