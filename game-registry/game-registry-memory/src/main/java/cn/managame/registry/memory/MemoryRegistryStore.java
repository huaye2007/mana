package cn.managame.registry.memory;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceNameEvent;
import cn.managame.registry.api.ServiceNameListener;
import cn.managame.registry.support.RegistryListeners;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared in-process storage behind {@link MemoryRegistry}.
 * <p>
 * Stores are keyed by namespace so that independently created bundles (e.g. a "server" bundle
 * and a "client" bundle in the same test JVM) observe the same data, mirroring how real backends
 * share state through an external service. Different namespaces are fully isolated.
 * <p>
 * All mutations, queries and listener registration are serialized under a single per-store lock;
 * events are dispatched synchronously on the mutating thread while the lock is held, which gives
 * watchers a total order of events. The monitor is reentrant, so listeners may call back into the
 * registry from the event thread without deadlocking.
 */
final class MemoryRegistryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryRegistryStore.class);
    private static final ConcurrentHashMap<String, MemoryRegistryStore> STORES = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    /** serviceName -> instance key -> instance. Guarded by {@code lock}. */
    private final Map<String, Map<String, ServiceInstance>> services = new HashMap<>();
    /** serviceName -> instance watchers. Guarded by {@code lock}. */
    private final Map<String, List<ServiceInstanceListener>> instanceListeners = new HashMap<>();
    /** Service-name watchers. Guarded by {@code lock}. */
    private final List<ServiceNameListener> nameListeners = new ArrayList<>();

    private MemoryRegistryStore() {
    }

    static MemoryRegistryStore namespace(String namespace) {
        return STORES.computeIfAbsent(namespace, key -> new MemoryRegistryStore());
    }

    static void reset(String namespace) {
        STORES.remove(namespace);
    }

    static void resetAll() {
        STORES.clear();
    }

    void register(ServiceInstance instance) {
        ServiceInstance copy = instance.copy();
        synchronized (lock) {
            Map<String, ServiceInstance> byKey = services.get(copy.getName());
            boolean newService = byKey == null;
            if (newService) {
                byKey = new HashMap<>();
                services.put(copy.getName(), byKey);
            }
            ServiceInstance old = byKey.put(copy.getKey(), copy);
            if (newService) {
                notifyNameListeners(new ServiceNameEvent(copy.getName(), DiscoveryEventType.ADDED));
            }
            if (old == null) {
                notifyInstanceListeners(copy.getName(),
                        new ServiceInstanceEvent(copy.getName(), DiscoveryEventType.ADDED, copy));
            } else if (!old.equals(copy)) {
                notifyInstanceListeners(copy.getName(),
                        new ServiceInstanceEvent(copy.getName(), DiscoveryEventType.UPDATED, copy));
            }
        }
    }

    void unregister(ServiceInstance instance) {
        synchronized (lock) {
            Map<String, ServiceInstance> byKey = services.get(instance.getName());
            if (byKey == null) {
                return;
            }
            ServiceInstance removed = byKey.remove(instance.getKey());
            if (removed == null) {
                return;
            }
            boolean serviceGone = byKey.isEmpty();
            if (serviceGone) {
                services.remove(instance.getName());
            }
            notifyInstanceListeners(instance.getName(),
                    new ServiceInstanceEvent(instance.getName(), DiscoveryEventType.REMOVED, removed));
            if (serviceGone) {
                notifyNameListeners(new ServiceNameEvent(instance.getName(), DiscoveryEventType.REMOVED));
            }
        }
    }

    Collection<ServiceInstance> getInstances(String serviceName) {
        synchronized (lock) {
            Map<String, ServiceInstance> byKey = services.get(serviceName);
            if (byKey == null || byKey.isEmpty()) {
                return List.of();
            }
            ArrayList<ServiceInstance> instances = new ArrayList<>(byKey.size());
            for (ServiceInstance instance : byKey.values()) {
                instances.add(instance.copy());
            }
            instances.sort(Comparator.comparing(ServiceInstance::getKey));
            return Collections.unmodifiableList(instances);
        }
    }

    Collection<String> getServiceNames() {
        synchronized (lock) {
            ArrayList<String> names = new ArrayList<>(services.keySet());
            Collections.sort(names);
            return Collections.unmodifiableList(names);
        }
    }

    /**
     * Emits the current instances of {@code serviceName} as ADDED events, then attaches the
     * listener — both under the store lock, so no event is missed or duplicated in between.
     */
    AutoCloseable watchInstances(String serviceName, ServiceInstanceListener listener) {
        synchronized (lock) {
            for (ServiceInstance instance : getInstances(serviceName)) {
                RegistryListeners.notify(LOGGER, listener,
                        new ServiceInstanceEvent(serviceName, DiscoveryEventType.ADDED, instance));
            }
            instanceListeners.computeIfAbsent(serviceName, key -> new ArrayList<>()).add(listener);
        }
        return () -> {
            synchronized (lock) {
                List<ServiceInstanceListener> listeners = instanceListeners.get(serviceName);
                if (listeners != null) {
                    listeners.remove(listener);
                    if (listeners.isEmpty()) {
                        instanceListeners.remove(serviceName);
                    }
                }
            }
        };
    }

    /** Same snapshot-then-attach protocol as {@link #watchInstances}, for service names. */
    AutoCloseable watchNames(ServiceNameListener listener) {
        synchronized (lock) {
            for (String serviceName : getServiceNames()) {
                RegistryListeners.notify(LOGGER, listener,
                        new ServiceNameEvent(serviceName, DiscoveryEventType.ADDED));
            }
            nameListeners.add(listener);
        }
        return () -> {
            synchronized (lock) {
                nameListeners.remove(listener);
            }
        };
    }

    private void notifyInstanceListeners(String serviceName, ServiceInstanceEvent event) {
        List<ServiceInstanceListener> listeners = instanceListeners.get(serviceName);
        if (listeners == null) {
            return;
        }
        for (ServiceInstanceListener listener : new ArrayList<>(listeners)) {
            RegistryListeners.notify(LOGGER, listener, event);
        }
    }

    private void notifyNameListeners(ServiceNameEvent event) {
        for (ServiceNameListener listener : new ArrayList<>(nameListeners)) {
            RegistryListeners.notify(LOGGER, listener, event);
        }
    }
}
