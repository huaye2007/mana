package cn.managame.registry.factory;

import cn.managame.registry.exception.RegistryOperationException;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public class RegistryConfig {
    private String type;
    private String endpoints;
    private String basePath = "/services";
    private long leaseTtlSeconds = 10L;
    private Properties properties = new Properties();

    public RegistryConfig() {
    }

    public RegistryConfig(RegistryConfig source) {
        Objects.requireNonNull(source, "source registry config must not be null");
        this.type = source.type;
        this.endpoints = source.endpoints;
        this.basePath = source.basePath;
        this.leaseTtlSeconds = source.leaseTtlSeconds;
        this.properties = copyOf(source.properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RegistryConfig copyOf(RegistryConfig source) {
        return new RegistryConfig(source);
    }

    public RegistryConfig copy() {
        return new RegistryConfig(this);
    }

    public RegistryType getType() {
        return RegistryType.fromType(type);
    }

    public String getTypeName() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setType(RegistryType type) {
        this.type = type == null ? null : type.type();
    }

    public String getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(String endpoints) {
        this.endpoints = endpoints;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public long getLeaseTtlSeconds() {
        return leaseTtlSeconds;
    }

    public void setLeaseTtlSeconds(long leaseTtlSeconds) {
        this.leaseTtlSeconds = leaseTtlSeconds;
    }

    public Properties getProperties() {
        return copyOf(properties);
    }

    public void setProperties(Properties properties) {
        this.properties = copyOf(properties);
    }

    public void setProperty(String key, String value) {
        putProperty(this.properties, key, value);
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

    public static final class Builder {
        private final RegistryConfig config = new RegistryConfig();

        private Builder() {
        }

        public Builder type(RegistryType type) {
            config.setType(type);
            return this;
        }

        public Builder type(String type) {
            config.setType(type);
            return this;
        }

        public Builder endpoints(String endpoints) {
            config.setEndpoints(endpoints);
            return this;
        }

        public Builder basePath(String basePath) {
            config.setBasePath(basePath);
            return this;
        }

        public Builder leaseTtlSeconds(long leaseTtlSeconds) {
            config.setLeaseTtlSeconds(leaseTtlSeconds);
            return this;
        }

        public Builder properties(Properties properties) {
            config.setProperties(properties);
            return this;
        }

        public Builder properties(Map<String, String> properties) {
            config.setProperties(copyOf(properties));
            return this;
        }

        public Builder property(String key, String value) {
            config.setProperty(key, value);
            return this;
        }

        public RegistryConfig build() {
            return config.copy();
        }
    }
}
