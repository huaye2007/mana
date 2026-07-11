package cn.managame.registry.api;

import java.util.Map;
import java.util.Objects;

/** Immutable description of a discoverable service endpoint. */
public final class ServiceInstance {
    private final String name;
    private final String id;
    private final String address;
    private final int port;
    private final double weight;
    private final boolean healthy;
    private final Map<String, String> metadata;

    private ServiceInstance(Builder builder) {
        name = requireText(builder.name, "name");
        address = requireText(builder.address, "address");
        if (builder.port < 1 || builder.port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (!Double.isFinite(builder.weight) || builder.weight <= 0) {
            throw new IllegalArgumentException("weight must be a positive finite number");
        }
        id = builder.id == null || builder.id.isBlank() ? null : builder.id.trim();
        port = builder.port;
        weight = builder.weight;
        healthy = builder.healthy;
        metadata = Map.copyOf(builder.metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return id;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public double getWeight() {
        return weight;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getKey() {
        return id == null ? address + ':' + port : id;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ServiceInstance that)) return false;
        return port == that.port && Double.compare(weight, that.weight) == 0 && healthy == that.healthy
                && name.equals(that.name) && Objects.equals(id, that.id) && address.equals(that.address)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, id, address, port, weight, healthy, metadata);
    }

    @Override
    public String toString() {
        return "ServiceInstance{name='" + name + "', id='" + id + "', address='" + address + "', port=" + port + '}';
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private String name;
        private String id;
        private String address;
        private int port;
        private double weight = 1.0;
        private boolean healthy = true;
        private Map<String, String> metadata = Map.of();

        private Builder() {
        }

        public Builder name(String value) { name = value; return this; }
        public Builder id(String value) { id = value; return this; }
        public Builder address(String value) { address = value; return this; }
        public Builder port(int value) { port = value; return this; }
        public Builder weight(double value) { weight = value; return this; }
        public Builder healthy(boolean value) { healthy = value; return this; }
        public Builder metadata(Map<String, String> value) { metadata = value == null ? Map.of() : Map.copyOf(value); return this; }

        public ServiceInstance build() {
            return new ServiceInstance(this);
        }
    }
}
