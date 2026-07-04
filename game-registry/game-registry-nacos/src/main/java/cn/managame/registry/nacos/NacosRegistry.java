package cn.managame.registry.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import cn.managame.registry.api.Discovery;
import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.Registry;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceNameEvent;
import cn.managame.registry.api.ServiceNameListener;
import cn.managame.registry.exception.RegistryConnectionException;
import cn.managame.registry.exception.RegistryOperationException;
import cn.managame.registry.support.CloseChain;
import cn.managame.registry.support.RegistryListeners;
import cn.managame.registry.support.RegistryWatchDiff;
import cn.managame.registry.support.RegistryWatchHandles;
import cn.managame.registry.support.RegistryProperties;
import cn.managame.registry.support.RegistryValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nacos implementation of Registry and Discovery.
 * <p>
 * Nacos-specific fields (namespace, group, cluster, ephemeral) are stored in
 * {@link ServiceInstance#getMetadata()} with well-known keys:
 * <ul>
 *   <li>{@code __nacos.namespace} - namespace</li>
 *   <li>{@code __nacos.group} - group name</li>
 *   <li>{@code __nacos.cluster} - cluster name</li>
 *   <li>{@code __nacos.ephemeral} - "true" or "false"</li>
 * </ul>
 */
public class NacosRegistry implements Registry, Discovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(NacosRegistry.class);

    /** Metadata key for Nacos namespace. */
    public static final String META_NAMESPACE = "__nacos.namespace";
    /** Metadata key for Nacos group. */
    public static final String META_GROUP = "__nacos.group";
    /** Metadata key for Nacos cluster. */
    public static final String META_CLUSTER = "__nacos.cluster";
    /** Metadata key for Nacos ephemeral flag. */
    public static final String META_EPHEMERAL = "__nacos.ephemeral";
    /** Metadata key for registration time. */
    public static final String META_REGISTRATION_TIME = "__nacos.registrationTime";
    public static final String PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS = "serviceNameWatchIntervalMillis";

    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    private static final String DEFAULT_CLUSTER = "DEFAULT";
    private static final String DEFAULT_NAMESPACE = "default";
    private static final long DEFAULT_SERVICE_NAME_WATCH_INTERVAL_MILLIS = 5000L;
    private static final String NACOS_GROUPED_NAME_SEPARATOR = "@@";

    private NamingService namingService;
    private final String serverAddr;
    private final Properties properties;
    private final String defaultGroup;
    private final String defaultCluster;
    private final String defaultNamespace;
    private final long serviceNameWatchIntervalMillis;
    private final ConcurrentMap<Long, AutoCloseable> watchHandles = new ConcurrentHashMap<>();
    private final AtomicLong listenerId = new AtomicLong(0);
    private final ScheduledExecutorService serviceNameWatchExecutor =
            Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    private volatile boolean closed;

    private static ThreadFactory daemonThreadFactory() {
        AtomicLong threadId = new AtomicLong(0);
        return task -> {
            Thread thread = new Thread(task, "nacos-service-name-watcher-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public NacosRegistry(String serverAddr) {
        this(serverAddr, new Properties());
    }

    public NacosRegistry(String serverAddr, Properties properties) {
        this.serverAddr = RegistryValidators.normalizeEndpoints(serverAddr);
        this.properties = new Properties();
        if (properties != null) {
            properties.forEach((key, value) -> RegistryProperties.putString(this.properties, key, value));
        }
        this.defaultGroup = RegistryProperties.firstNonBlank(
                DEFAULT_GROUP,
                this.properties.getProperty(META_GROUP),
                this.properties.getProperty("nacos.group"),
                this.properties.getProperty("group")
        );
        this.defaultCluster = RegistryProperties.firstNonBlank(
                DEFAULT_CLUSTER,
                this.properties.getProperty(META_CLUSTER),
                this.properties.getProperty("nacos.cluster"),
                this.properties.getProperty("cluster")
        );
        this.defaultNamespace = RegistryProperties.firstNonBlank(
                DEFAULT_NAMESPACE,
                this.properties.getProperty(META_NAMESPACE),
                this.properties.getProperty("namespaceId"),
                this.properties.getProperty("namespace")
        );
        this.serviceNameWatchIntervalMillis = RegistryProperties.positiveLong(
                PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS,
                RegistryProperties.firstNonBlank(
                        String.valueOf(DEFAULT_SERVICE_NAME_WATCH_INTERVAL_MILLIS),
                        this.properties.getProperty(PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS),
                        this.properties.getProperty("nacos." + PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS),
                        null
                )
        );
    }

    public boolean isStarted() {
        return namingService != null;
    }

    public boolean isClosed() {
        return closed;
    }

    public int getActiveWatchCount() {
        return watchHandles.size();
    }

    public long getServiceNameWatchIntervalMillis() {
        return serviceNameWatchIntervalMillis;
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new RegistryOperationException("Nacos registry has been closed");
        }
        if (namingService != null) {
            return;
        }
        try {
            if (properties.isEmpty()) {
                this.namingService = NamingFactory.createNamingService(serverAddr);
            } else {
                properties.put("serverAddr", serverAddr);
                this.namingService = NamingFactory.createNamingService(properties);
            }
        } catch (Exception e) {
            this.namingService = null;
            throw new RegistryConnectionException("Failed to start nacos registry", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        CloseChain chain = new CloseChain();
        for (AutoCloseable handle : new ArrayList<>(watchHandles.values())) {
            chain.step("Failed to close nacos watch", handle::close);
        }
        watchHandles.clear();
        serviceNameWatchExecutor.shutdownNow();
        if (namingService != null) {
            NamingService current = namingService;
            namingService = null;
            chain.step("Failed to close nacos naming service", current::shutDown);
        }
        chain.step("Interrupted while stopping nacos service-name watch executor", () -> {
            try {
                if (!serviceNameWatchExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.warn("Nacos service-name watch executor did not terminate in 2s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        });
        chain.throwIfFailed();
    }

    @Override
    public void register(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        validateRegistryScopedMetadata(serviceInstance);
        Instance instance = toNacosInstance(serviceInstance);
        try {
            namingService.registerInstance(serviceInstance.getName(), defaultGroup, instance);
        } catch (NacosException e) {
            throw new RegistryOperationException("Failed to register service in nacos: " + serviceInstance.getName(), e);
        }
    }

    @Override
    public void unregister(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        validateRegistryScopedMetadata(serviceInstance);
        Instance instance = toNacosInstance(serviceInstance);
        try {
            namingService.deregisterInstance(
                    serviceInstance.getName(),
                    defaultGroup,
                    instance
            );
        } catch (NacosException e) {
            throw new RegistryOperationException("Failed to unregister service in nacos: " + serviceInstance.getName(), e);
        }
    }

    @Override
    public Collection<ServiceInstance> getInstances(String serviceName) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        return Collections.unmodifiableList(queryInstances(serviceName));
    }

    private List<ServiceInstance> queryInstances(String serviceName) {
        List<Instance> nacosInstances;
        try {
            nacosInstances = namingService.getAllInstances(serviceName, defaultGroup);
        } catch (NacosException e) {
            throw new RegistryOperationException("Failed to query instances in nacos: " + serviceName, e);
        }
        List<ServiceInstance> result = new ArrayList<>();
        if (nacosInstances != null) {
            for (Instance nacosInstance : nacosInstances) {
                if (nacosInstance != null) {
                    result.add(fromNacosInstance(serviceName, defaultGroup, nacosInstance));
                }
            }
        }
        result.sort(Comparator.comparing(ServiceInstance::getKey));
        return result;
    }

    @Override
    public Collection<String> getServiceNames() {
        assertStarted();
        Set<String> names = new HashSet<>();
        int page = 1;
        int pageSize = 200;
        while (true) {
            ListView<String> view;
            try {
                view = namingService.getServicesOfServer(page, pageSize, defaultGroup);
            } catch (NacosException e) {
                throw new RegistryOperationException("Failed to query service names in nacos", e);
            }
            if (view == null || view.getData() == null || view.getData().isEmpty()) {
                break;
            }
            for (String name : view.getData()) {
                if (RegistryValidators.isValidServiceName(LOGGER, name)) {
                    names.add(name);
                }
            }
            if (view.getData().size() < pageSize) {
                break;
            }
            page++;
        }
        List<String> sortedNames = new ArrayList<>(names);
        Collections.sort(sortedNames);
        return Collections.unmodifiableList(sortedNames);
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        RegistryValidators.validateListener(listener);
        ConcurrentMap<String, ServiceInstance> known = new ConcurrentHashMap<>();
        EventListener nacosListener = event -> {
            if (!(event instanceof NamingEvent namingEvent)) {
                return;
            }
            handleNamingEvent(serviceName, listener, known, namingEvent);
        };
        boolean subscribed = false;
        try {
            namingService.subscribe(serviceName, defaultGroup, nacosListener);
            subscribed = true;
            refreshWatchedInstances(serviceName, listener, known);
            return registerWatchHandle(() -> unsubscribeService(serviceName, nacosListener));
        } catch (NacosException e) {
            throw new RegistryOperationException("Failed to subscribe nacos service: " + serviceName, e);
        } catch (RuntimeException e) {
            if (subscribed) {
                unsubscribeService(serviceName, nacosListener);
            }
            throw e;
        }
    }

    private void refreshWatchedInstances(
            String serviceName,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> known
    ) {
        RegistryWatchDiff.emitInstanceChanges(
                serviceName,
                listener,
                known,
                getInstances(serviceName),
                LOGGER);
    }

    private void unsubscribeService(String serviceName, EventListener listener) {
        try {
            if (namingService != null) {
                namingService.unsubscribe(serviceName, defaultGroup, listener);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to unsubscribe nacos service {}", serviceName, e);
        }
    }

    @Override
    public AutoCloseable watchServiceNames(ServiceNameListener listener) {
        assertStarted();
        RegistryValidators.validateListener(listener);
        // Nacos has no first-class service-name subscription that works across all server versions,
        // so service-name watch polls getServiceNames() on a single daemon thread at
        // serviceNameWatchIntervalMillis and diffs the result. Instance-level changes still use the
        // native subscribe() path in watchService().
        Set<String> known = new HashSet<>(getServiceNames());
        for (String name : known) {
            RegistryListeners.notify(LOGGER, listener, new ServiceNameEvent(name, DiscoveryEventType.ADDED));
        }
        AtomicBoolean watchClosed = new AtomicBoolean(false);
        ScheduledFuture<?> future;
        try {
            future = serviceNameWatchExecutor.scheduleWithFixedDelay(
                    () -> pollServiceNames(listener, known, watchClosed),
                    serviceNameWatchIntervalMillis,
                    serviceNameWatchIntervalMillis,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            throw new RegistryOperationException("Nacos registry has been closed", e);
        }
        return registerWatchHandle(() -> {
            watchClosed.set(true);
            future.cancel(true);
        });
    }

    private String normalizedGroupedServiceName(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        String prefix = defaultGroup + NACOS_GROUPED_NAME_SEPARATOR;
        if (serviceName.startsWith(prefix)) {
            return serviceName.substring(prefix.length());
        }
        return serviceName;
    }

    private void pollServiceNames(
            ServiceNameListener listener,
            Set<String> known,
            AtomicBoolean watchClosed) {
        if (watchClosed.get() || closed || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            Set<String> current = new HashSet<>(getServiceNames());
            for (String name : current) {
                if (!known.contains(name)) {
                    RegistryListeners.notify(LOGGER, listener, new ServiceNameEvent(name, DiscoveryEventType.ADDED));
                }
            }
            for (String name : new HashSet<>(known)) {
                if (!current.contains(name)) {
                    RegistryListeners.notify(LOGGER, listener, new ServiceNameEvent(name, DiscoveryEventType.REMOVED));
                }
            }
            known.clear();
            known.addAll(current);
        } catch (Exception e) {
            if (!watchClosed.get() && !closed) {
                LOGGER.warn("Failed to poll nacos service names", e);
            }
        }
    }

    private Instance toNacosInstance(ServiceInstance serviceInstance) {
        validateRegistryScopedMetadata(serviceInstance);
        Instance instance = new Instance();
        instance.setIp(serviceInstance.getAddress());
        instance.setPort(serviceInstance.getPort());
        instance.setServiceName(serviceInstance.getName());
        instance.setInstanceId(serviceInstance.getKey());
        instance.setWeight(serviceInstance.getWeight());
        instance.setHealthy(serviceInstance.isHealthy());
        instance.setEphemeral(getMetadataBool(serviceInstance, META_EPHEMERAL, true));
        instance.setClusterName(getMetadata(serviceInstance, META_CLUSTER, defaultCluster));
        Map<String, String> sourceMetadata = serviceInstance.getMetadata();
        Map<String, String> metadata = sourceMetadata == null ? new HashMap<>() : new HashMap<>(sourceMetadata);
        metadata.put("id", serviceInstance.getId() == null ? "" : serviceInstance.getId());
        metadata.put(META_GROUP, defaultGroup);
        metadata.put(META_CLUSTER, instance.getClusterName());
        metadata.put(META_NAMESPACE, defaultNamespace);
        metadata.put(META_REGISTRATION_TIME, String.valueOf(serviceInstance.getRegistrationTimeUTC()));
        instance.setMetadata(metadata);
        return instance;
    }

    private ServiceInstance fromNacosInstance(String serviceName, String group, Instance nacosInstance) {
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setAddress(nacosInstance.getIp());
        instance.setPort(nacosInstance.getPort());
        instance.setWeight(nacosInstance.getWeight());
        instance.setHealthy(nacosInstance.isHealthy());
        Map<String, String> metadata = nacosInstance.getMetadata();
        if (metadata != null) {
            Map<String, String> copied = RegistryProperties.copyStringMap(metadata);
            boolean hasOriginalId = copied.containsKey("id");
            String originalId = copied.remove("id");
            if (hasOriginalId) {
                instance.setId(originalId);
            } else {
                instance.setId(nacosInstance.getInstanceId());
            }
            String timeStr = copied.remove(META_REGISTRATION_TIME);
            instance.setRegistrationTimeUTC(parseRegistrationTimeUTC(timeStr));
            // Store Nacos-specific fields in metadata
            copied.put(META_CLUSTER, nacosClusterName(nacosInstance));
            copied.put(META_EPHEMERAL, String.valueOf(nacosInstance.isEphemeral()));
            copied.put(META_NAMESPACE, defaultNamespace);
            copied.put(META_GROUP, group);
            instance.setMetadata(copied);
        } else {
            Map<String, String> copied = new HashMap<>();
            instance.setId(nacosInstance.getInstanceId());
            instance.setRegistrationTimeUTC(0L);
            copied.put(META_CLUSTER, nacosClusterName(nacosInstance));
            copied.put(META_EPHEMERAL, String.valueOf(nacosInstance.isEphemeral()));
            copied.put(META_NAMESPACE, defaultNamespace);
            copied.put(META_GROUP, group);
            instance.setMetadata(copied);
        }
        return instance;
    }

    private String nacosClusterName(Instance nacosInstance) {
        return RegistryProperties.firstNonBlank(defaultCluster, nacosInstance.getClusterName());
    }

    private long parseRegistrationTimeUTC(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return 0L;
        }
        try {
            long registrationTimeUTC = Long.parseLong(timeStr.trim());
            return registrationTimeUTC < 0L ? 0L : registrationTimeUTC;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void handleNamingEvent(
            String serviceName,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> known,
            NamingEvent namingEvent) {
        try {
            String eventServiceName = normalizedGroupedServiceName(namingEvent.getServiceName());
            if (!serviceName.equals(eventServiceName)) {
                LOGGER.warn(
                        "Ignoring nacos naming event for unexpected service {}, expected {}",
                        namingEvent.getServiceName(),
                        serviceName
                );
                return;
            }
            Map<String, ServiceInstance> current = new HashMap<>();
            List<Instance> nacosInstances = namingEvent.getInstances();
            if (nacosInstances != null) {
                for (Instance nacosInstance : nacosInstances) {
                    if (nacosInstance == null) {
                        continue;
                    }
                    ServiceInstance instance = fromNacosInstance(serviceName, defaultGroup, nacosInstance);
                    current.put(instance.getKey(), instance);
                }
            }
            RegistryWatchDiff.emitInstanceChanges(
                    serviceName,
                    listener,
                    known,
                    current.values(),
                    LOGGER);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to handle nacos naming event for service {}", serviceName, e);
        }
    }

    private AutoCloseable registerWatchHandle(AutoCloseable closeable) {
        return RegistryWatchHandles.register(
                watchHandles,
                listenerId,
                this,
                () -> closed,
                closeable,
                "Nacos registry has been closed",
                "Failed to close nacos watch after registry closed");
    }

    private void assertStarted() {
        if (closed) {
            throw new RegistryOperationException("Nacos registry has been closed");
        }
        if (namingService == null) {
            throw new RegistryOperationException("Nacos registry has not been started");
        }
    }

    private String getMetadata(ServiceInstance instance, String key, String defaultValue) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null) return defaultValue;
        String value = metadata.get(key);
        return value != null ? value : defaultValue;
    }

    private boolean getMetadataBool(ServiceInstance instance, String key, boolean defaultValue) {
        String value = getMetadata(instance, key, null);
        return RegistryProperties.booleanValue(value, defaultValue);
    }

    private void validateRegistryScopedMetadata(ServiceInstance instance) {
        validateMetadataMatchesDefault(instance, META_GROUP, defaultGroup);
        validateMetadataMatchesDefault(instance, META_NAMESPACE, defaultNamespace);
    }

    private void validateMetadataMatchesDefault(ServiceInstance instance, String key, String expected) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null) {
            return;
        }
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.trim().equals(expected)) {
            throw new RegistryOperationException(
                    key + " must match registry configuration: expected " + expected + " but was " + value.trim());
        }
    }

}
