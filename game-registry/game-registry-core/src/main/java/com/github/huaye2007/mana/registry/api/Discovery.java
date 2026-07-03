package com.github.huaye2007.mana.registry.api;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import com.github.huaye2007.mana.registry.support.RegistryValidators;

import java.util.Collection;

public interface Discovery extends AutoCloseable {
    Collection<ServiceInstance> getInstances(String serviceName);

    Collection<String> getServiceNames();

    default AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        RegistryValidators.validateServiceName(serviceName);
        if (listener == null) {
            throw new RegistryOperationException("listener must not be null");
        }
        throw new RegistryOperationException("watchService is not supported by this discovery implementation");
    }

    default AutoCloseable watchServiceNames(ServiceNameListener listener) {
        if (listener == null) {
            throw new RegistryOperationException("listener must not be null");
        }
        throw new RegistryOperationException("watchServiceNames is not supported by this discovery implementation");
    }

    void start();

    @Override
    void close();
}
