package cn.managame.registry.memory;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local registry. Clients using the same endpoint string share one namespace. */
public final class MemoryRegistry implements ServiceRegistry {
    private static final Map<String, Store> STORES = new ConcurrentHashMap<>();

    private final String owner = UUID.randomUUID().toString();
    private final Store store;
    private final Set<Watch> watches = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    MemoryRegistry(String namespace) {
        store = STORES.computeIfAbsent(namespace, ignored -> new Store());
    }

    @Override
    public void register(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        ServiceInstanceEvent event;
        List<Watch> listeners;
        synchronized (store) {
            Map<String, Entry> service = store.services.computeIfAbsent(instance.getName(), ignored -> new LinkedHashMap<>());
            Entry previous = service.put(instance.getKey(), new Entry(owner, instance));
            if (previous != null && previous.instance.equals(instance)) return;
            event = new ServiceInstanceEvent(previous == null ? DiscoveryEventType.ADDED : DiscoveryEventType.UPDATED, instance);
            listeners = store.listeners(instance.getName());
        }
        notifyListeners(listeners, event);
    }

    @Override
    public void deregister(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        removeOwned(instance.getName(), instance.getKey());
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName) {
        requireOpen();
        requireServiceName(serviceName);
        synchronized (store) {
            Map<String, Entry> service = store.services.get(serviceName);
            if (service == null) return List.of();
            return service.values().stream().map(Entry::instance).toList();
        }
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        requireOpen();
        requireServiceName(serviceName);
        Objects.requireNonNull(listener, "listener");
        Watch watch = new Watch(serviceName, listener);
        List<ServiceInstance> initial;
        synchronized (store) {
            store.watches.computeIfAbsent(serviceName, ignored -> new ArrayList<>()).add(watch);
            Map<String, Entry> service = store.services.get(serviceName);
            initial = service == null ? List.of() : service.values().stream().map(Entry::instance).toList();
        }
        watches.add(watch);
        try {
            watch.initialize(initial);
            return watch;
        } catch (RuntimeException e) {
            watch.close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List.copyOf(watches).forEach(Watch::close);
        List<ServiceInstance> removed = new ArrayList<>();
        Map<String, List<Watch>> listenersByService = new HashMap<>();
        synchronized (store) {
            store.services.forEach((name, instances) -> {
                instances.values().removeIf(entry -> {
                    if (!entry.owner.equals(owner)) return false;
                    removed.add(entry.instance);
                    return true;
                });
                listenersByService.put(name, store.listeners(name));
            });
            store.services.values().removeIf(Map::isEmpty);
        }
        removed.forEach(instance -> notifyListeners(listenersByService.get(instance.getName()),
                new ServiceInstanceEvent(DiscoveryEventType.REMOVED, instance)));
    }

    private void removeOwned(String serviceName, String key) {
        ServiceInstance removed = null;
        List<Watch> listeners = List.of();
        synchronized (store) {
            Map<String, Entry> service = store.services.get(serviceName);
            if (service != null) {
                Entry entry = service.get(key);
                if (entry != null && entry.owner.equals(owner)) {
                    removed = service.remove(key).instance;
                    if (service.isEmpty()) store.services.remove(serviceName);
                    listeners = store.listeners(serviceName);
                }
            }
        }
        if (removed != null) notifyListeners(listeners,
                new ServiceInstanceEvent(DiscoveryEventType.REMOVED, removed));
    }

    private static void notifyListeners(List<Watch> listeners, ServiceInstanceEvent event) {
        if (listeners == null) return;
        for (Watch watch : listeners) watch.accept(event);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("registry is closed");
    }

    private static void requireServiceName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("service name must not be blank");
    }

    private record Entry(String owner, ServiceInstance instance) {
    }

    private static final class Store {
        private final Map<String, Map<String, Entry>> services = new HashMap<>();
        private final Map<String, List<Watch>> watches = new HashMap<>();

        private List<Watch> listeners(String serviceName) {
            List<Watch> listeners = watches.get(serviceName);
            return listeners == null ? List.of() : List.copyOf(listeners);
        }
    }

    private final class Watch implements AutoCloseable {
        private final String serviceName;
        private final ServiceInstanceListener listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final List<ServiceInstanceEvent> pending = new ArrayList<>();
        private boolean initialized;

        private Watch(String serviceName, ServiceInstanceListener listener) {
            this.serviceName = serviceName;
            this.listener = listener;
        }

        private synchronized void initialize(List<ServiceInstance> initial) {
            if (!active.get()) return;
            initial.forEach(instance -> listener.onEvent(
                    new ServiceInstanceEvent(DiscoveryEventType.ADDED, instance)));
            pending.forEach(listener::onEvent);
            pending.clear();
            initialized = true;
        }

        private synchronized void accept(ServiceInstanceEvent event) {
            if (!active.get()) return;
            if (!initialized) {
                pending.add(event);
                return;
            }
            listener.onEvent(event);
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) return;
            synchronized (store) {
                List<Watch> serviceWatches = store.watches.get(serviceName);
                if (serviceWatches != null) {
                    serviceWatches.remove(this);
                    if (serviceWatches.isEmpty()) store.watches.remove(serviceName);
                }
            }
            watches.remove(this);
        }
    }
}
