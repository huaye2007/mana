package cn.managame.registry.api;

import cn.managame.registry.exception.RegistryOperationException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ServiceInstance {
    private String name;
    private String id;
    private String address;
    private int port;
    private double weight = 1.0D;
    private boolean healthy = true;
    private Map<String, String> metadata = new HashMap<>();
    private long registrationTimeUTC = System.currentTimeMillis();

    public ServiceInstance() {
    }

    public ServiceInstance(String name, String id, String address, int port) {
        this.name = name;
        this.id = id;
        this.address = address;
        this.port = port;
    }

    public ServiceInstance(ServiceInstance source) {
        Objects.requireNonNull(source, "source service instance must not be null");
        this.name = source.name;
        this.id = source.id;
        this.address = source.address;
        this.port = source.port;
        this.weight = source.weight;
        this.healthy = source.healthy;
        this.metadata = copyMetadata(source.metadata);
        this.registrationTimeUTC = source.registrationTimeUTC;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ServiceInstance copyOf(ServiceInstance source) {
        return new ServiceInstance(source);
    }

    public ServiceInstance copy() {
        return new ServiceInstance(this);
    }

    /**
     * Returns a unique key for this instance, used for caching and deduplication.
     * Prefers id if present, otherwise falls back to address:port.
     */
    public String getKey() {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return address + ":" + port;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = copyMetadata(metadata);
    }

    public long getRegistrationTimeUTC() {
        return registrationTimeUTC;
    }

    public void setRegistrationTimeUTC(long registrationTimeUTC) {
        this.registrationTimeUTC = registrationTimeUTC;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceInstance that = (ServiceInstance) o;
        return port == that.port
                && Double.compare(that.weight, weight) == 0
                && healthy == that.healthy
                && registrationTimeUTC == that.registrationTimeUTC
                && Objects.equals(name, that.name)
                && Objects.equals(id, that.id)
                && Objects.equals(address, that.address)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, id, address, port, weight, healthy, metadata, registrationTimeUTC);
    }

    @Override
    public String toString() {
        return "ServiceInstance{name='" + name + "', id='" + id + "', address='" + address + "', port=" + port + '}';
    }

    public static final class Builder {
        private final ServiceInstance instance = new ServiceInstance();

        private Builder() {
        }

        public Builder name(String name) {
            instance.setName(name);
            return this;
        }

        public Builder id(String id) {
            instance.setId(id);
            return this;
        }

        public Builder address(String address) {
            instance.setAddress(address);
            return this;
        }

        public Builder port(int port) {
            instance.setPort(port);
            return this;
        }

        public Builder weight(double weight) {
            instance.setWeight(weight);
            return this;
        }

        public Builder healthy(boolean healthy) {
            instance.setHealthy(healthy);
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            instance.setMetadata(metadata);
            return this;
        }

        public Builder metadata(String key, String value) {
            validateMetadataEntry(key, value);
            instance.metadata.put(key, value);
            return this;
        }

        public Builder registrationTimeUTC(long registrationTimeUTC) {
            instance.setRegistrationTimeUTC(registrationTimeUTC);
            return this;
        }

        public ServiceInstance build() {
            return instance.copy();
        }
    }

    private static HashMap<String, String> copyMetadata(Map<String, String> source) {
        HashMap<String, String> copy = new HashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                validateMetadataEntry(key, value);
                copy.put(key, value);
            });
        }
        return copy;
    }

    private static void validateMetadataEntry(String key, String value) {
        if (key == null || value == null) {
            throw new RegistryOperationException("metadata keys and values must not be null");
        }
    }
}
