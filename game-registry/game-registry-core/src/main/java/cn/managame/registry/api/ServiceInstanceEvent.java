package cn.managame.registry.api;

import java.util.Objects;

public final class ServiceInstanceEvent {
    private final DiscoveryEventType type;
    private final ServiceInstance instance;

    public ServiceInstanceEvent(DiscoveryEventType type, ServiceInstance instance) {
        this.type = Objects.requireNonNull(type, "type");
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    public DiscoveryEventType getType() {
        return type;
    }

    public ServiceInstance getInstance() {
        return instance;
    }
}
