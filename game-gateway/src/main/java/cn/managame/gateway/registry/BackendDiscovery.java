package cn.managame.gateway.registry;

import cn.managame.gateway.router.BackendRouterManager;
import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** Reconciles registry events into both the RPC pool and the routing snapshot. */
public final class BackendDiscovery implements AutoCloseable {
    private final ServiceRegistry registry;
    private final String backendServiceName;
    private final BackendRouterManager routerManager;
    private final BackendConnector connector;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ConcurrentHashMap<String, ServiceInstance> connected = new ConcurrentHashMap<>();
    private AutoCloseable watch;

    public BackendDiscovery(ServiceRegistry registry, String backendServiceName,
                            BackendRouterManager routerManager, BackendConnector connector) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (backendServiceName == null || backendServiceName.isBlank()) throw new IllegalArgumentException("backendServiceName must not be blank");
        this.backendServiceName = backendServiceName;
        this.routerManager = Objects.requireNonNull(routerManager, "routerManager");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        try {
            watch = registry.watchService(backendServiceName, this::onEvent);
        } catch (RuntimeException error) {
            started.set(false);
            throw error;
        }
    }

    private synchronized void onEvent(ServiceInstanceEvent event) {
        if (!started.get()) return;
        ServiceInstance incoming = event.getInstance();
        ServiceInstance previous = routerManager.get(incoming.getKey());
        if (event.getType() == DiscoveryEventType.REMOVED || !incoming.isHealthy()) {
            ServiceInstance removed = previous != null ? previous : incoming;
            routerManager.remove(removed);
            connector.disconnectBackend(removed);
            connected.remove(removed.getKey());
            return;
        }
        if (previous != null && (!previous.getAddress().equals(incoming.getAddress()) || previous.getPort() != incoming.getPort())) {
            connector.disconnectBackend(previous);
        }
        connector.connectBackend(incoming);
        connected.put(incoming.getKey(), incoming);
        routerManager.upsert(incoming);
    }

    @Override
    public synchronized void close() {
        if (!started.compareAndSet(true, false)) return;
        Exception failure = null;
        try { if (watch != null) watch.close(); } catch (Exception error) { failure = error; }
        for (ServiceInstance instance : connected.values()) {
            try { connector.disconnectBackend(instance); } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            routerManager.remove(instance);
        }
        connected.clear();
        if (failure != null) throw new IllegalStateException("cannot close backend discovery", failure);
    }
}
