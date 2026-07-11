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

    NacosRegistry(RegistryConfig config) {
        this(createNamingService(config), config.getProperties().getOrDefault(GROUP_PROPERTY, DEFAULT_GROUP));
    }

    NacosRegistry(NamingService namingService, String group) {
        this.namingService = Objects.requireNonNull(namingService, "namingService");
        this.group = group == null || group.isBlank() ? DEFAULT_GROUP : group.trim();
    }

    @Override
    public void register(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        String key = registrationKey(instance);
        ServiceInstance previous = registrations.get(key);
        try {
            namingService.registerInstance(instance.getName(), group, toNacos(instance));
            if (previous != null && endpointChanged(previous, instance)) {
                try {
                    namingService.deregisterInstance(previous.getName(), group, toNacos(previous));
                } catch (NacosException e) {
                    try {
                        namingService.deregisterInstance(instance.getName(), group, toNacos(instance));
                    } catch (NacosException rollback) {
                        e.addSuppressed(rollback);
                    }
                    throw e;
                }
            }
            registrations.put(key, instance);
        } catch (NacosException e) {
            throw failure("register " + instance, e);
        }
    }

    @Override
    public void deregister(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        ServiceInstance owned = registrations.get(registrationKey(instance));
        if (owned == null) return;
        try {
            namingService.deregisterInstance(owned.getName(), group, toNacos(owned));
            registrations.remove(registrationKey(owned), owned);
        } catch (NacosException e) {
            throw failure("deregister " + owned, e);
        }
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName) {
        requireOpen();
        requireServiceName(serviceName);
        try {
            return namingService.getAllInstances(serviceName, group).stream()
                    .map(instance -> fromNacos(serviceName, instance)).toList();
        } catch (NacosException e) {
            throw failure("get instances for " + serviceName, e);
        }
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        requireOpen();
        requireServiceName(serviceName);
        Objects.requireNonNull(listener, "listener");
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RegistryException failure = null;
        for (NacosWatch watch : List.copyOf(watches)) {
            try {
                watch.close();
            } catch (RegistryException e) {
                failure = append(failure, e);
            }
        }
        for (ServiceInstance instance : List.copyOf(registrations.values())) {
            try {
                namingService.deregisterInstance(instance.getName(), group, toNacos(instance));
                registrations.remove(registrationKey(instance), instance);
            } catch (NacosException e) {
                failure = append(failure, failure("deregister " + instance + " while closing", e));
            }
        }
        try {
            namingService.shutDown();
        } catch (NacosException e) {
            failure = append(failure, failure("shutdown Nacos client", e));
        }
        if (failure != null) throw failure;
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
