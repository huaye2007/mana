package com.github.huaye2007.mana.registry.api;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;

public class ServiceNameEvent {
    private final String serviceName;
    private final DiscoveryEventType type;

    public ServiceNameEvent(String serviceName, DiscoveryEventType type) {
        validateServiceName(serviceName);
        if (type == null) {
            throw new RegistryOperationException("event type must not be null");
        }
        if (type == DiscoveryEventType.UPDATED) {
            throw new RegistryOperationException("service name event type must be ADDED or REMOVED");
        }
        this.serviceName = serviceName;
        this.type = type;
    }

    public String getServiceName() {
        return serviceName;
    }

    public DiscoveryEventType getType() {
        return type;
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
