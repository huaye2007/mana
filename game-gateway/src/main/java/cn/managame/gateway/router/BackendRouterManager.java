package cn.managame.gateway.router;

import cn.managame.gateway.session.GatewaySession;
import cn.managame.registry.api.ServiceInstance;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the live backend snapshot and per-session sticky assignment. */
public final class BackendRouterManager {
    private final Router router;
    private final ConcurrentHashMap<String, ServiceInstance> instances = new ConcurrentHashMap<>();

    public BackendRouterManager(Router router) { this.router = Objects.requireNonNull(router, "router"); }

    public synchronized void upsert(ServiceInstance instance) {
        instances.put(instance.getKey(), instance);
        refresh();
    }

    public synchronized void remove(ServiceInstance instance) {
        instances.remove(instance.getKey());
        refresh();
    }

    public ServiceInstance get(String serviceId) { return serviceId == null ? null : instances.get(serviceId); }
    public boolean isAlive(String serviceId) {
        ServiceInstance instance = get(serviceId);
        return instance != null && instance.isHealthy();
    }
    public int instanceCount() { return instances.size(); }

    public ServiceInstance resolve(GatewaySession session) {
        String sticky = session.getBackendServiceId();
        ServiceInstance selected = isAlive(sticky) ? instances.get(sticky) : router.select(session.routeKey());
        session.setBackendServiceId(selected == null ? null : selected.getKey());
        return selected;
    }

    private void refresh() { router.refresh(new ArrayList<>(instances.values())); }
}
