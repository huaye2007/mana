package cn.managame.registry.nacos;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.exception.RegistryException;
import cn.managame.registry.factory.RegistryConfig;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NacosRegistry implements ServiceRegistry {
    static final String GROUP_PROPERTY = "group";
    static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    static final String ID_METADATA = "mana.instance.id";

    private final NamingService namingService;
    private final String group;
    private final Map<String, ServiceInstance> registrations = new ConcurrentHashMap<>();
    private final Set<NacosWatch> watches = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    NacosRegistry(RegistryConfig config) {
        this(createNamingService(config), config.getProperties().getOrDefault(GROUP_PROPERTY, DEFAULT_GROUP));
    }

    NacosRegistry(NamingService namingService, String group) {
        this.namingService = Objects.requireNonNull(namingService, "namingService");
        this.group = group == null || group.isBlank() ? DEFAULT_GROUP : group.trim();
    }

    @Override
    public void register(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        synchronized (lifecycleLock) {
            requireOpen();
            String key = registrationKey(instance);
            ServiceInstance previous = registrations.get(key);
            List<ServiceInstance> before = serviceRegistrations(instance.getName(), null, null);
            List<ServiceInstance> desired = serviceRegistrations(instance.getName(), key, instance);
            try {
                // Nacos keeps reconnect redo state per service. Always publish the complete
                // service-local set so multiple registrations survive a client reconnect.
                namingService.batchRegisterInstance(instance.getName(), group, toNacos(desired));
                if (previous != null && endpointChanged(previous, instance)) {
                    // batchDeregister preserves the current batch redo set while removing
                    // an obsolete endpoint that may still exist on the server.
                    namingService.batchDeregisterInstance(previous.getName(), group, List.of(toNacos(previous)));
                }
                registrations.put(key, instance);
            } catch (NacosException e) {
                // Nacos updates its redo cache before the remote request completes, so
                // restore that cache even when batchRegisterInstance itself throws.
                rollbackService(instance.getName(), before, desired, e);
                throw failure("register " + instance, e);
            }
        }
    }

    @Override
    public void deregister(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        synchronized (lifecycleLock) {
            requireOpen();
            ServiceInstance owned = registrations.get(registrationKey(instance));
            if (owned == null) return;
            try {
                namingService.batchDeregisterInstance(owned.getName(), group, List.of(toNacos(owned)));
                registrations.remove(registrationKey(owned), owned);
            } catch (NacosException e) {
                throw failure("deregister " + owned, e);
            }
        }
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName) {
        requireServiceName(serviceName);
        synchronized (lifecycleLock) {
            requireOpen();
            try {
                return namingService.getAllInstances(serviceName, group).stream()
                        .map(instance -> fromNacos(serviceName, instance)).toList();
            } catch (NacosException e) {
                throw failure("get instances for " + serviceName, e);
            }
        }
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        requireServiceName(serviceName);
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            requireOpen();
            NacosWatch watch = new NacosWatch(serviceName, listener);
            try {
                namingService.subscribe(serviceName, group, watch);
                List<ServiceInstance> initial = namingService.getAllInstances(serviceName, group).stream()
                        .map(instance -> fromNacos(serviceName, instance)).toList();
                watches.add(watch);
                try {
                    watch.initialize(initial);
                    return watch;
                } catch (RuntimeException e) {
                    try {
                        watch.close();
                    } catch (RuntimeException closeFailure) {
                        e.addSuppressed(closeFailure);
                    }
                    throw e;
                }
            } catch (NacosException e) {
                try {
                    namingService.unsubscribe(serviceName, group, watch);
                } catch (NacosException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw failure("watch service " + serviceName, e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            RegistryException failure = null;
            for (NacosWatch watch : List.copyOf(watches)) {
                try {
                    watch.close();
                } catch (RegistryException e) {
                    failure = append(failure, e);
                }
            }
            try {
                Map<String, List<Instance>> byService = new LinkedHashMap<>();
                registrations.values().forEach(instance -> byService
                        .computeIfAbsent(instance.getName(), ignored -> new ArrayList<>()).add(toNacos(instance)));
                for (Map.Entry<String, List<Instance>> entry : byService.entrySet()) {
                    try {
                        namingService.batchDeregisterInstance(entry.getKey(), group, entry.getValue());
                        registrations.values().removeIf(instance -> instance.getName().equals(entry.getKey()));
                    } catch (NacosException e) {
                        failure = append(failure, failure("deregister " + entry.getKey() + " instances while closing", e));
                    }
                }
            } catch (RuntimeException e) {
                failure = append(failure, new RegistryException("failed to prepare Nacos registrations for closing", e));
            }
            try {
                namingService.shutDown();
            } catch (NacosException e) {
                failure = append(failure, failure("shutdown Nacos client", e));
            }
            if (failure != null) throw failure;
        }
    }

    private List<ServiceInstance> serviceRegistrations(String serviceName, String replacedKey,
                                                        ServiceInstance replacement) {
        List<ServiceInstance> result = registrations.entrySet().stream()
                .filter(entry -> entry.getValue().getName().equals(serviceName))
                .filter(entry -> !entry.getKey().equals(replacedKey))
                .map(Map.Entry::getValue)
                .sorted(java.util.Comparator.comparing(ServiceInstance::getKey))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (replacement != null) result.add(replacement);
        result.sort(java.util.Comparator.comparing(ServiceInstance::getKey));
        return result;
    }

    private static List<Instance> toNacos(List<ServiceInstance> instances) {
        return instances.stream().map(NacosRegistry::toNacos).toList();
    }

    private void rollbackService(String serviceName, List<ServiceInstance> previous,
                                 List<ServiceInstance> attempted, NacosException failure) {
        try {
            if (previous.isEmpty()) {
                namingService.batchDeregisterInstance(serviceName, group, toNacos(attempted));
            } else {
                namingService.batchRegisterInstance(serviceName, group, toNacos(previous));
            }
        } catch (NacosException rollback) {
            failure.addSuppressed(rollback);
        }
    }

    private static NamingService createNamingService(RegistryConfig config) {
        Properties properties = new Properties();
        properties.putAll(config.getProperties());
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, config.getEndpoints());
        try {
            return NacosFactory.createNamingService(properties);
        } catch (NacosException e) {
            throw failure("create Nacos client", e);
        }
    }

    static Instance toNacos(ServiceInstance source) {
        Instance target = new Instance();
        target.setInstanceId(source.getKey());
        target.setIp(source.getAddress());
        target.setPort(source.getPort());
        target.setWeight(source.getWeight());
        target.setHealthy(source.isHealthy());
        target.setEnabled(true);
        target.setEphemeral(true);
        Map<String, String> metadata = new HashMap<>(source.getMetadata());
        metadata.put(ID_METADATA, source.getKey());
        target.setMetadata(metadata);
        return target;
    }

    static ServiceInstance fromNacos(String serviceName, Instance source) {
        Map<String, String> metadata = new HashMap<>(source.getMetadata() == null ? Map.of() : source.getMetadata());
        String id = metadata.remove(ID_METADATA);
        if (id == null || id.isBlank()) id = source.getInstanceId();
        return ServiceInstance.builder()
                .name(serviceName)
                .id(id)
                .address(source.getIp())
                .port(source.getPort())
                .weight(source.getWeight())
                .healthy(source.isHealthy())
                .metadata(metadata)
                .build();
    }

    private static String registrationKey(ServiceInstance instance) {
        return instance.getName() + '\0' + instance.getKey();
    }

    private static boolean endpointChanged(ServiceInstance first, ServiceInstance second) {
        return !first.getAddress().equals(second.getAddress()) || first.getPort() != second.getPort();
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("registry is closed");
    }

    private static void requireServiceName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("service name must not be blank");
    }

    private static RegistryException failure(String action, NacosException cause) {
        return new RegistryException("failed to " + action, cause);
    }

    private static RegistryException append(RegistryException current, RegistryException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private final class NacosWatch implements EventListener, AutoCloseable {
        private final String serviceName;
        private final ServiceInstanceListener listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Map<String, ServiceInstance> snapshot;
        private List<ServiceInstance> pending;

        private NacosWatch(String serviceName, ServiceInstanceListener listener) {
            this.serviceName = serviceName;
            this.listener = listener;
        }

        @Override
        public void onEvent(Event event) {
            if (!(event instanceof NamingEvent namingEvent) || !active.get()) return;
            List<ServiceInstance> instances = namingEvent.getInstances().stream()
                    .map(instance -> fromNacos(serviceName, instance)).toList();
            acceptSnapshot(instances);
        }

        private void initialize(List<ServiceInstance> initial) {
            synchronized (this) {
                List<ServiceInstance> latest = pending == null ? initial : pending;
                pending = null;
                snapshot = index(latest);
                latest.forEach(instance -> listener.onEvent(
                        new ServiceInstanceEvent(DiscoveryEventType.ADDED, instance)));
            }
        }

        private void acceptSnapshot(List<ServiceInstance> nextInstances) {
            List<ServiceInstanceEvent> events;
            synchronized (this) {
                if (snapshot == null) {
                    pending = nextInstances;
                    return;
                }
                Map<String, ServiceInstance> next = index(nextInstances);
                events = diff(snapshot, next);
                snapshot = next;
            }
            events.forEach(listener::onEvent);
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (!active.compareAndSet(true, false)) return;
                try {
                    namingService.unsubscribe(serviceName, group, this);
                } catch (NacosException e) {
                    throw failure("stop watching " + serviceName, e);
                } finally {
                    watches.remove(this);
                }
            }
        }
    }

    private static Map<String, ServiceInstance> index(List<ServiceInstance> instances) {
        Map<String, ServiceInstance> result = new LinkedHashMap<>();
        instances.forEach(instance -> result.put(instance.getKey(), instance));
        return result;
    }

    private static List<ServiceInstanceEvent> diff(Map<String, ServiceInstance> previous,
                                                   Map<String, ServiceInstance> next) {
        List<ServiceInstanceEvent> events = new ArrayList<>();
        previous.forEach((key, instance) -> {
            if (!next.containsKey(key)) events.add(new ServiceInstanceEvent(DiscoveryEventType.REMOVED, instance));
        });
        next.forEach((key, instance) -> {
            ServiceInstance old = previous.get(key);
            if (old == null) events.add(new ServiceInstanceEvent(DiscoveryEventType.ADDED, instance));
            else if (!old.equals(instance)) events.add(new ServiceInstanceEvent(DiscoveryEventType.UPDATED, instance));
        });
        return events;
    }
}
