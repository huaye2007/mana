package cn.managame.registry.api;

import java.util.List;

public interface ServiceRegistry extends AutoCloseable {
    void register(ServiceInstance instance);

    void deregister(ServiceInstance instance);

    List<ServiceInstance> getInstances(String serviceName);

    /** Registers a listener and synchronously emits ADDED events for the current snapshot. */
    AutoCloseable watchService(String serviceName, ServiceInstanceListener listener);

    @Override
    void close();
}
