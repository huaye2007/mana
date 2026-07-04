package cn.managame.registry.memory;

import cn.managame.registry.api.Discovery;
import cn.managame.registry.api.Registry;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceNameListener;
import cn.managame.registry.exception.RegistryOperationException;
import cn.managame.registry.support.CloseChain;
import cn.managame.registry.support.RegistryValidators;
import cn.managame.registry.support.RegistryWatchHandles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure in-memory {@link Registry} / {@link Discovery} backend. No network, no external process —
 * intended for unit / integration tests and local development where spinning up zookeeper/etcd/
 * nacos/consul is overkill.
 * <p>
 * Registries created with the same namespace (the {@code endpoints} field when built through the
 * provider) share one in-process store, so separately created bundles can discover each other
 * inside a single JVM. Instances registered through this registry behave like ephemeral
 * registrations: {@link #close()} automatically unregisters them, mirroring what happens when a
 * real backend's session/lease expires.
 * <p>
 * Watch events are dispatched synchronously on the mutating thread (see the threading contract on
 * {@link ServiceInstanceListener} / {@link ServiceNameListener}).
 */
public class MemoryRegistry implements Registry, Discovery {
    public static final String DEFAULT_NAMESPACE = "local";

    private final MemoryRegistryStore store;
    private final ConcurrentMap<Long, AutoCloseable> watchHandles = new ConcurrentHashMap<>();
    private final AtomicLong watchId = new AtomicLong(0);
    /** name/key -> instance registered through this registry; unregistered on close. Guarded by {@code this}. */
    private final Map<String, ServiceInstance> ownInstances = new LinkedHashMap<>();
    private volatile boolean started;
    private volatile boolean closed;

    public MemoryRegistry() {
        this(DEFAULT_NAMESPACE);
    }

    public MemoryRegistry(String namespace) {
        RegistryValidators.requireNonBlank(namespace, "namespace");
        this.store = MemoryRegistryStore.namespace(namespace.trim());
    }

    /** Drops all data and watchers of one namespace. Test hygiene helper. */
    public static void resetNamespace(String namespace) {
        MemoryRegistryStore.reset(namespace);
    }

    /** Drops all namespaces. Test hygiene helper. */
    public static void resetAllNamespaces() {
        MemoryRegistryStore.resetAll();
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isClosed() {
        return closed;
    }

    public int getActiveWatchCount() {
        return watchHandles.size();
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new RegistryOperationException("Memory registry has been closed");
        }
        started = true;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        CloseChain chain = new CloseChain();
        for (ServiceInstance instance : new ArrayList<>(ownInstances.values())) {
            chain.step("Failed to unregister memory registry instance on close",
                    () -> store.unregister(instance));
        }
        ownInstances.clear();
        for (AutoCloseable closeable : new ArrayList<>(watchHandles.values())) {
            chain.step("Failed to close memory registry watch", closeable::close);
        }
        watchHandles.clear();
        chain.throwIfFailed();
    }

    @Override
    public void register(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        ServiceInstance copy = serviceInstance.copy();
        synchronized (this) {
            store.register(copy);
            ownInstances.put(ownKey(copy), copy);
        }
    }

    @Override
    public void unregister(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        synchronized (this) {
            store.unregister(serviceInstance);
            ownInstances.remove(ownKey(serviceInstance));
        }
    }

    @Override
    public Collection<ServiceInstance> getInstances(String serviceName) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        return store.getInstances(serviceName);
    }

    @Override
    public Collection<String> getServiceNames() {
        assertStarted();
        return store.getServiceNames();
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        RegistryValidators.validateListener(listener);
        return registerWatchHandle(store.watchInstances(serviceName, listener));
    }

    @Override
    public AutoCloseable watchServiceNames(ServiceNameListener listener) {
        assertStarted();
        RegistryValidators.validateListener(listener);
        return registerWatchHandle(store.watchNames(listener));
    }

    private AutoCloseable registerWatchHandle(AutoCloseable closeable) {
        return RegistryWatchHandles.register(
                watchHandles,
                watchId,
                this,
                () -> closed,
                closeable,
                "Memory registry has been closed",
                "Failed to close memory registry watch after registry closed");
    }

    private void assertStarted() {
        if (closed) {
            throw new RegistryOperationException("Memory registry has been closed");
        }
        if (!started) {
            throw new RegistryOperationException("Memory registry has not been started");
        }
    }

    /** serviceName cannot contain '/', so the pair is unambiguous. */
    private static String ownKey(ServiceInstance instance) {
        return instance.getName() + "/" + instance.getKey();
    }
}
