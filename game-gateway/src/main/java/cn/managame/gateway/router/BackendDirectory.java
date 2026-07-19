package cn.managame.gateway.router;

import cn.managame.gateway.session.GatewaySession;
import cn.managame.registry.api.ServiceInstance;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Live backend instances grouped by logical service name. */
public final class BackendDirectory {
    private final ConcurrentHashMap<String, ServiceBackends> services = new ConcurrentHashMap<>();

    public void upsert(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        service(instance.getName()).upsert(instance);
    }

    public void remove(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        ServiceBackends backends = services.get(instance.getName());
        if (backends != null) backends.remove(instance.getKey());
    }

    public ServiceInstance get(String serviceName, String serviceId) {
        ServiceBackends backends = services.get(serviceName);
        return backends == null ? null : backends.get(serviceId);
    }

    public ServiceInstance resolve(String serviceName, GatewaySession session) {
        return service(serviceName).resolve(session, serviceName);
    }

    public int serviceCount() { return services.size(); }

    public int instanceCount(String serviceName) {
        ServiceBackends backends = services.get(serviceName);
        return backends == null ? 0 : backends.instanceCount();
    }

    private ServiceBackends service(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        return services.computeIfAbsent(serviceName, ignored -> new ServiceBackends());
    }

    private static final class ServiceBackends {
        private final ConsistentHashRouter router = new ConsistentHashRouter();
        private final ConcurrentHashMap<String, ServiceInstance> instances = new ConcurrentHashMap<>();

        synchronized void upsert(ServiceInstance instance) {
            instances.put(instance.getKey(), instance);
            refresh();
        }

        synchronized void remove(String serviceId) {
            if (instances.remove(serviceId) != null) refresh();
        }

        ServiceInstance get(String serviceId) {
            return serviceId == null ? null : instances.get(serviceId);
        }

        int instanceCount() { return instances.size(); }

        ServiceInstance resolve(GatewaySession session, String serviceName) {
            String sticky = session.getBackendServiceId(serviceName);
            ServiceInstance selected = get(sticky);
            if (selected == null || !selected.isHealthy()) selected = router.select(session.routeKey());
            session.setBackendServiceId(serviceName, selected == null ? null : selected.getKey());
            return selected;
        }

        private void refresh() { router.refresh(new ArrayList<>(instances.values())); }
    }
}
