package cn.managame.registry.api;

import cn.managame.registry.exception.RegistryOperationException;

public class ServiceInstanceEvent {
    private final String serviceName;
    private final DiscoveryEventType type;
    private final ServiceInstance instance;

    public ServiceInstanceEvent(String serviceName, DiscoveryEventType type, ServiceInstance instance) {
        validateServiceName(serviceName);
        if (type == null) {
            throw new RegistryOperationException("event type must not be null");
        }
        if (instance == null) {
            throw new RegistryOperationException("event instance must not be null");
        }
        if (!serviceName.equals(instance.getName())) {
            throw new RegistryOperationException("event instance name must match event serviceName");
        }
        this.serviceName = serviceName;
        this.type = type;
        this.instance = instance.copy();
    }

    public String getServiceName() {
        return serviceName;
    }

    public DiscoveryEventType getType() {
        return type;
    }

    public ServiceInstance getInstance() {
        return instance.copy();
    }

    private void validateServiceName(String value) {
        if (value == null || value.isBlank()) {
            throw new RegistryOperationException("event serviceName must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw new RegistryOperationException("event serviceName must not contain leading or trailing whitespace");
        }
        if (value.contains("/")) {
            throw new RegistryOperationException("event serviceName must not contain /");
        }
    }
}
