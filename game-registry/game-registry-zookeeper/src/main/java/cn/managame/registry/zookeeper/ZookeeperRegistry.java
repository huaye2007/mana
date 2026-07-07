package cn.managame.registry.zookeeper;

import cn.managame.registry.api.Discovery;
import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.Registry;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceNameEvent;
import cn.managame.registry.api.ServiceNameListener;
import cn.managame.registry.exception.RegistryConnectionException;
import cn.managame.registry.exception.RegistryOperationException;
import cn.managame.common.io.CloseChain;
import cn.managame.registry.support.RegistryListeners;
import cn.managame.registry.support.RegistryWatchDiff;
import cn.managame.registry.support.RegistryWatchHandles;
import cn.managame.registry.support.RegistryProperties;
import cn.managame.registry.support.RegistryValidators;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ZookeeperRegistry implements Registry, Discovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperRegistry.class);
    public static final String PROP_AUTH_SCHEME = "authScheme";
    public static final String PROP_AUTH = "auth";
    public static final String PROP_CONNECTION_TIMEOUT_MS = "connectionTimeoutMillis";
    public static final String PROP_SESSION_TIMEOUT_MS = "sessionTimeoutMillis";
    public static final String PROP_RETRY_BASE_SLEEP_MS = "retryBaseSleepMillis";
    public static final String PROP_RETRY_MAX_RETRIES = "retryMaxRetries";
    public static final String PROP_CONNECTION_WAIT_MS = "connectionWaitMillis";

    private static final int DEFAULT_RETRY_BASE_SLEEP_MS = 1000;
    private static final int DEFAULT_RETRY_MAX_RETRIES = 3;
    private static final int DEFAULT_CONNECTION_WAIT_MS = 10_000;

    private final CuratorFramework client;
    private final ServiceDiscovery<ServiceInstancePayload> serviceDiscovery;
    private final boolean managedClient;
    private final String basePath;
    private final int connectionWaitMs;
    private final ConcurrentMap<Long, AutoCloseable> watchHandles = new ConcurrentHashMap<>();
    private final AtomicLong watchId = new AtomicLong(0);
    private volatile boolean started;
    private volatile boolean closed;

    public ZookeeperRegistry(String connectionString, String basePath) {
        this(connectionString, basePath, new Properties());
    }

    public ZookeeperRegistry(String connectionString, String basePath, Properties properties) {
        String normalizedConnectionString = RegistryValidators.normalizeEndpoints(connectionString);
        String normalizedBasePath = RegistryValidators.normalizeBasePath(basePath);
        Properties options = properties == null ? new Properties() : properties;
        this.client = createClient(normalizedConnectionString, options);
        this.managedClient = true;
        this.basePath = normalizedBasePath;
        this.connectionWaitMs = RegistryProperties.positiveInt(
                options, PROP_CONNECTION_WAIT_MS, DEFAULT_CONNECTION_WAIT_MS);
        this.serviceDiscovery = ServiceDiscoveryBuilder.builder(ServiceInstancePayload.class)
                .client(client)
                .basePath(normalizedBasePath)
                .serializer(new JsonInstanceSerializer<>(ServiceInstancePayload.class))
                .build();
    }

    private CuratorFramework createClient(String connectionString, Properties properties) {
        int retryBaseSleepMs = RegistryProperties.positiveInt(
                properties, PROP_RETRY_BASE_SLEEP_MS, DEFAULT_RETRY_BASE_SLEEP_MS);
        int retryMaxRetries = RegistryProperties.positiveInt(
                properties, PROP_RETRY_MAX_RETRIES, DEFAULT_RETRY_MAX_RETRIES);
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(connectionString)
                .retryPolicy(new ExponentialBackoffRetry(retryBaseSleepMs, retryMaxRetries));
        RegistryProperties.applyPositiveInt(properties, PROP_CONNECTION_TIMEOUT_MS, builder::connectionTimeoutMs);
        RegistryProperties.applyPositiveInt(properties, PROP_SESSION_TIMEOUT_MS, builder::sessionTimeoutMs);
        String authScheme = RegistryProperties.get(properties, PROP_AUTH_SCHEME);
        String auth = RegistryProperties.get(properties, PROP_AUTH);
        boolean hasAuthScheme = authScheme != null;
        boolean hasAuth = auth != null;
        if (hasAuthScheme != hasAuth) {
            throw new RegistryOperationException("authScheme and auth must be set together");
        }
        if (hasAuthScheme) {
            builder.authorization(authScheme, auth.getBytes(StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    public ZookeeperRegistry(CuratorFramework client, String basePath) {
        if (client == null) {
            throw new RegistryOperationException("zookeeper client must not be null");
        }
        String normalizedBasePath = RegistryValidators.normalizeBasePath(basePath);
        this.client = client;
        this.managedClient = false;
        this.basePath = normalizedBasePath;
        this.connectionWaitMs = DEFAULT_CONNECTION_WAIT_MS;
        this.serviceDiscovery = ServiceDiscoveryBuilder.builder(ServiceInstancePayload.class)
                .client(client)
                .basePath(normalizedBasePath)
                .serializer(new JsonInstanceSerializer<>(ServiceInstancePayload.class))
                .build();
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
            throw new RegistryOperationException("Zookeeper registry has been closed");
        }
        if (started) {
            return;
        }
        try {
            if (client.getState() != CuratorFrameworkState.STARTED) {
                client.start();
            }
            if (!client.blockUntilConnected(connectionWaitMs, TimeUnit.MILLISECONDS)) {
                throw new RegistryConnectionException(
                        "Timed out waiting for zookeeper connection after " + connectionWaitMs + "ms");
            }
            serviceDiscovery.start();
            started = true;
        } catch (Exception e) {
            throw new RegistryConnectionException("Failed to start zookeeper registry", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        CloseChain chain = new CloseChain(RegistryOperationException::new);
        for (AutoCloseable closeable : new ArrayList<>(watchHandles.values())) {
            chain.step("Failed to close zookeeper watch", closeable::close);
        }
        watchHandles.clear();
        chain.step("Failed to close zookeeper service discovery", serviceDiscovery::close);
        if (managedClient) {
            chain.step("Failed to close zookeeper client", client::close);
        }
        chain.throwIfFailed();
    }

    @Override
    public void register(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        try {
            serviceDiscovery.registerService(toCurator(serviceInstance));
        } catch (Exception e) {
            throw new RegistryOperationException(
                    "Failed to register service in zookeeper: " + serviceInstance.getName(), e);
        }
    }

    @Override
    public void unregister(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        try {
            serviceDiscovery.unregisterService(toCurator(serviceInstance));
        } catch (Exception e) {
            throw new RegistryOperationException(
                    "Failed to unregister service in zookeeper: " + serviceInstance.getName(), e);
        }
    }

    @Override
    public Collection<ServiceInstance> getInstances(String serviceName) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        try {
            Collection<org.apache.curator.x.discovery.ServiceInstance<ServiceInstancePayload>> queried =
                    serviceDiscovery.queryForInstances(serviceName);
            ArrayList<ServiceInstance> instances = new ArrayList<>();
            if (queried != null) {
                for (org.apache.curator.x.discovery.ServiceInstance<ServiceInstancePayload> curatorInstance : queried) {
                    if (curatorInstance != null) {
                        instances.add(fromCurator(curatorInstance, serviceName));
                    }
                }
            }
            instances.sort(Comparator.comparing(ServiceInstance::getKey));
            return Collections.unmodifiableList(instances);
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to query instances in zookeeper: " + serviceName, e);
        }
    }

    @Override
    public Collection<String> getServiceNames() {
        assertStarted();
        try {
            Set<String> names = new HashSet<>();
            Collection<String> queried = serviceDiscovery.queryForNames();
            if (queried != null) {
                for (String name : queried) {
                    if (RegistryValidators.isValidServiceName(LOGGER, name)) {
                        names.add(name);
                    }
                }
            }
            ArrayList<String> sortedNames = new ArrayList<>(names);
            Collections.sort(sortedNames);
            return Collections.unmodifiableList(sortedNames);
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to query service names in zookeeper", e);
        }
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        RegistryValidators.validateListener(listener);
        try {
            String normalizedBasePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1)
                    : basePath;
            CuratorCache cache = newCuratorCache(normalizedBasePath + "/" + serviceName);
            AtomicBoolean cacheClosed = new AtomicBoolean(false);
            AutoCloseable cacheHandle = closeCacheOnce(cache, cacheClosed);
            ConcurrentMap<String, ServiceInstance> previous = new ConcurrentHashMap<>();
            JsonInstanceSerializer<ServiceInstancePayload> serializer = new JsonInstanceSerializer<>(ServiceInstancePayload.class);

            try {
                cache.listenable().addListener((type, oldData, data) -> {
                    try {
                        ChildData eventData = type == CuratorCacheListener.Type.NODE_DELETED ? oldData : data;
                        if (eventData != null && eventData.getData() != null
                                && eventData.getData().length > 0) {
                            org.apache.curator.x.discovery.ServiceInstance<ServiceInstancePayload> curInst = serializer
                                    .deserialize(eventData.getData());
                            ServiceInstance instance = fromCurator(curInst, serviceName);
                            handleWatchedInstanceEvent(serviceName, listener, previous, type, instance);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to handle zookeeper watch event for service {}", serviceName, e);
                    }
                });

                cache.start();

                RegistryWatchDiff.emitInstanceChanges(
                        serviceName,
                        listener,
                        previous,
                        getInstances(serviceName),
                        LOGGER);

                return registerWatchHandle(cacheHandle);
            } catch (Exception e) {
                new CloseChain(RegistryOperationException::new).step("Failed to close zookeeper service watch after start failure", cacheHandle::close);
                throw e;
            }
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to start zookeeper watch for service: " + serviceName, e);
        }
    }

    private void handleWatchedInstanceEvent(
            String serviceName,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> previous,
            CuratorCacheListener.Type eventType,
            ServiceInstance instance
    ) {
        String key = instance.getKey();
        if (eventType == CuratorCacheListener.Type.NODE_CREATED
                || eventType == CuratorCacheListener.Type.NODE_CHANGED) {
            ServiceInstance old = previous.put(key, instance);
            if (old == null) {
                RegistryListeners.notify(LOGGER, listener, new ServiceInstanceEvent(serviceName, DiscoveryEventType.ADDED, instance));
            } else if (!old.equals(instance)) {
                RegistryListeners.notify(LOGGER, listener, new ServiceInstanceEvent(serviceName, DiscoveryEventType.UPDATED, instance));
            }
        } else if (eventType == CuratorCacheListener.Type.NODE_DELETED) {
            ServiceInstance removed = previous.remove(key);
            if (removed != null) {
                RegistryListeners.notify(LOGGER, listener, new ServiceInstanceEvent(serviceName, DiscoveryEventType.REMOVED, removed));
            }
        }
    }

    @Override
    public AutoCloseable watchServiceNames(ServiceNameListener listener) {
        assertStarted();
        RegistryValidators.validateListener(listener);
        try {
            CuratorCache cache = newCuratorCache(basePath);
            AtomicBoolean cacheClosed = new AtomicBoolean(false);
            AutoCloseable cacheHandle = closeCacheOnce(cache, cacheClosed);
            Set<String> known = ConcurrentHashMap.newKeySet();
            try {
                cache.listenable().addListener((type, oldData, data) -> {
                    ChildData eventData = type == CuratorCacheListener.Type.NODE_DELETED ? oldData : data;
                    if (eventData == null || !isDirectServicePath(eventData.getPath())) {
                        return;
                    }
                    String serviceName = extractServiceName(eventData.getPath());
                    if (serviceName == null || serviceName.isEmpty()) {
                        return;
                    }
                    if (type == CuratorCacheListener.Type.NODE_CREATED) {
                        if (known.add(serviceName)) {
                            RegistryListeners.notify(LOGGER, listener,
                                    new ServiceNameEvent(serviceName, DiscoveryEventType.ADDED));
                        }
                    } else if (type == CuratorCacheListener.Type.NODE_DELETED) {
                        if (known.remove(serviceName)) {
                            RegistryListeners.notify(LOGGER, listener,
                                    new ServiceNameEvent(serviceName, DiscoveryEventType.REMOVED));
                        }
                    }
                });
                cache.start();
                RegistryWatchDiff.emitServiceNameChanges(
                        listener,
                        known,
                        getServiceNames(),
                        LOGGER);

                return registerWatchHandle(cacheHandle);
            } catch (Exception e) {
                new CloseChain(RegistryOperationException::new).step("Failed to close zookeeper service-name watch after start failure",
                        cacheHandle::close);
                throw e;
            }
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to start zookeeper watch for service names", e);
        }
    }

    CuratorCache newCuratorCache(String path) {
        return CuratorCache.build(client, path);
    }

    private AutoCloseable closeCacheOnce(CuratorCache cache, AtomicBoolean cacheClosed) {
        return () -> {
            if (cacheClosed.compareAndSet(false, true)) {
                cache.close();
            }
        };
    }

    private AutoCloseable registerWatchHandle(AutoCloseable closeable) {
        return RegistryWatchHandles.register(
                watchHandles,
                watchId,
                this,
                () -> closed,
                closeable,
                "Zookeeper registry has been closed",
                "Failed to close zookeeper watch after registry closed");
    }

    private void assertStarted() {
        if (closed) {
            throw new RegistryOperationException("Zookeeper registry has been closed");
        }
        if (!started) {
            throw new RegistryOperationException("Zookeeper registry has not been started");
        }
    }

    private String extractServiceName(String path) {
        if (path == null) {
            return null;
        }
        String normalizedBasePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        if (!path.startsWith(normalizedBasePath + "/")) {
            return null;
        }
        String relative = path.substring(normalizedBasePath.length() + 1);
        int idx = relative.indexOf('/');
        if (idx >= 0) {
            return relative.substring(0, idx);
        }
        return relative;
    }

    private boolean isDirectServicePath(String path) {
        if (path == null) {
            return false;
        }
        String normalizedBasePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        if (!path.startsWith(normalizedBasePath + "/")) {
            return false;
        }
        String relative = path.substring(normalizedBasePath.length() + 1);
        return !relative.isEmpty() && relative.indexOf('/') < 0;
    }

    private org.apache.curator.x.discovery.ServiceInstance<ServiceInstancePayload> toCurator(ServiceInstance serviceInstance)
            throws Exception {
        ServiceInstancePayload payload = new ServiceInstancePayload(
                serviceInstance.getId() == null ? "" : serviceInstance.getId(),
                serviceInstance.getWeight(),
                serviceInstance.isHealthy(),
                serviceInstance.getMetadata()
        );
        return org.apache.curator.x.discovery.ServiceInstance.<ServiceInstancePayload>builder()
                .name(serviceInstance.getName())
                .id(serviceInstance.getKey())
                .address(serviceInstance.getAddress())
                .port(serviceInstance.getPort())
                .payload(payload)
                .registrationTimeUTC(serviceInstance.getRegistrationTimeUTC())
                .build();
    }

    private ServiceInstance fromCurator(org.apache.curator.x.discovery.ServiceInstance<ServiceInstancePayload> curatorInstance) {
        return fromCurator(curatorInstance, curatorInstance.getName());
    }

    private ServiceInstance fromCurator(
            org.apache.curator.x.discovery.ServiceInstance<ServiceInstancePayload> curatorInstance,
            String serviceName) {
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId(curatorInstance.getId());
        instance.setAddress(curatorInstance.getAddress());
        instance.setPort(curatorInstance.getPort());
        instance.setRegistrationTimeUTC(curatorInstance.getRegistrationTimeUTC());
        ServiceInstancePayload payload = curatorInstance.getPayload();
        if (payload != null) {
            if (payload.getId() != null) {
                instance.setId(payload.getId());
            }
            instance.setWeight(payload.getWeight());
            instance.setHealthy(payload.isHealthy());
            if (payload.getMetadata() != null) {
                instance.setMetadata(RegistryProperties.copyStringMap(payload.getMetadata()));
            }
        }
        return instance;
    }

    /**
     * Payload class to carry weight, healthy, and metadata through Curator's ServiceDiscovery.
     * Previously these fields were lost during serialization.
     */
    public static class ServiceInstancePayload {
        private String id;
        private double weight = 1.0D;
        private boolean healthy = true;
        private Map<String, String> metadata = new HashMap<>();

        public ServiceInstancePayload() {
        }

        public ServiceInstancePayload(double weight, boolean healthy, Map<String, String> metadata) {
            this(null, weight, healthy, metadata);
        }

        public ServiceInstancePayload(String id, double weight, boolean healthy, Map<String, String> metadata) {
            this.id = id;
            this.weight = weight;
            this.healthy = healthy;
            setMetadata(metadata);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        public Map<String, String> getMetadata() {
            return new HashMap<>(metadata);
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = RegistryProperties.copyStringMap(metadata);
        }
    }
}
