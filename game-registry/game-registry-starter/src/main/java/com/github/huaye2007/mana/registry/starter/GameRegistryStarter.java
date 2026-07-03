package com.github.huaye2007.mana.registry.starter;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import com.github.huaye2007.mana.registry.factory.RegistryBundle;
import com.github.huaye2007.mana.registry.factory.RegistryConfig;
import com.github.huaye2007.mana.registry.factory.RegistryFactory;
import com.github.huaye2007.mana.registry.factory.RegistryType;

import java.util.Map;
import java.util.Properties;

/**
 * Small bootstrap entry for applications that want to use game-registry directly
 * without assembling RegistryConfig by hand.
 */
public final class GameRegistryStarter {
    private GameRegistryStarter() {
    }

    public static RegistryBundle create(RegistryType type, String endpoints) {
        return builder().type(type).endpoints(endpoints).create();
    }

    public static RegistryBundle create(String type, String endpoints) {
        return builder().type(type).endpoints(endpoints).create();
    }

    public static RegistryBundle start(RegistryType type, String endpoints) {
        return builder().type(type).endpoints(endpoints).start();
    }

    public static RegistryBundle start(String type, String endpoints) {
        return builder().type(type).endpoints(endpoints).start();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String type;
        private String endpoints;
        private String basePath;
        private Long leaseTtlSeconds;
        private final Properties properties = new Properties();

        private Builder() {
        }

        public Builder type(RegistryType type) {
            this.type = type == null ? null : type.type();
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder endpoints(String endpoints) {
            this.endpoints = endpoints;
            return this;
        }

        public Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public Builder leaseTtlSeconds(long leaseTtlSeconds) {
            this.leaseTtlSeconds = leaseTtlSeconds;
            return this;
        }

        public Builder properties(Properties properties) {
            Properties copy = copyOf(properties);
            this.properties.clear();
            this.properties.putAll(copy);
            return this;
        }

        public Builder properties(Map<String, String> properties) {
            Properties copy = copyOf(properties);
            this.properties.clear();
            this.properties.putAll(copy);
            return this;
        }

        public Builder property(String key, String value) {
            putProperty(this.properties, key, value);
            return this;
        }

        public RegistryConfig buildConfig() {
            RegistryConfig.Builder builder = RegistryConfig.builder()
                    .type(type)
                    .endpoints(endpoints)
                    .properties(properties);
            if (basePath != null) {
                builder.basePath(basePath);
            }
            if (leaseTtlSeconds != null) {
                builder.leaseTtlSeconds(leaseTtlSeconds);
            }
            return builder.build();
        }

        public RegistryBundle create() {
            return RegistryFactory.create(buildConfig());
        }

        public RegistryBundle start() {
            RegistryBundle bundle = create();
            bundle.start();
            return bundle;
        }

        private static Properties copyOf(Properties source) {
            Properties copy = new Properties();
            if (source != null) {
                source.forEach((key, value) -> putProperty(copy, key, value));
            }
            return copy;
        }

        private static Properties copyOf(Map<String, String> source) {
            Properties copy = new Properties();
            if (source != null) {
                source.forEach((key, value) -> putProperty(copy, key, value));
            }
            return copy;
        }

        private static void putProperty(Properties target, Object key, Object value) {
            if (key == null) {
                throw new RegistryOperationException("property key must not be blank");
            }
            if (!(key instanceof String propertyKey)) {
                throw new RegistryOperationException("property key must be a string");
            }
            if (propertyKey.isBlank()) {
                throw new RegistryOperationException("property key must not be blank");
            }
            if (value == null) {
                throw new RegistryOperationException("property value must not be null");
            }
            if (!(value instanceof String propertyValue)) {
                throw new RegistryOperationException("property value must be a string");
            }
            target.setProperty(propertyKey, propertyValue);
        }
    }
}
