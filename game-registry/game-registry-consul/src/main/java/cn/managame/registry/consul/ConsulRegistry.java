package cn.managame.registry.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.agent.model.NewCheck;
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.health.model.Check;
import com.ecwid.consul.v1.health.model.HealthService;
import cn.managame.registry.api.Discovery;
import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.Registry;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceNameListener;
import cn.managame.registry.exception.RegistryConnectionException;
import cn.managame.registry.exception.RegistryOperationException;
import cn.managame.registry.support.CloseChain;
import cn.managame.registry.support.RegistryWatchDiff;
import cn.managame.registry.support.RegistryWatchHandles;
import cn.managame.registry.support.RegistryProperties;
import cn.managame.registry.support.RegistryValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ConsulRegistry implements Registry, Discovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsulRegistry.class);

    public static final String META_TAGS = "__consul.tags";
    public static final String META_ID = "__consul.id";
    public static final String META_WEIGHT = "__consul.weight";
    public static final String META_HEALTHY = "__consul.healthy";
    public static final String META_REGISTRATION_TIME = "__consul.registrationTime";

    private static final String CONSUL_SERVICE_ID_SEPARATOR = "@@";
    public static final String PROP_ACL_TOKEN = "aclToken";
    public static final String PROP_BLOCKING_QUERY_WAIT_SECONDS = "blockingQueryWaitSeconds";
    public static final String PROP_PING_ON_START = "pingOnStart";
    public static final String PROP_TAGS = "tags";
    public static final String PROP_CHECK_HTTP = "checkHttp";
    public static final String PROP_CHECK_TCP = "checkTcp";
    public static final String PROP_CHECK_INTERVAL = "checkInterval";
    public static final String PROP_CHECK_TIMEOUT = "checkTimeout";
    public static final String PROP_CHECK_DEREGISTER_CRITICAL_SERVICE_AFTER = "checkDeregisterCriticalServiceAfter";
    public static final String PROP_CHECK_TLS_SKIP_VERIFY = "checkTlsSkipVerify";
    public static final String PROP_CHECK_METHOD = "checkMethod";
    public static final String PROP_CHECK_STATUS = "checkStatus";
    public static final String PROP_WATCH_SHUTDOWN_TIMEOUT_MILLIS = "watchShutdownTimeoutMillis";
    public static final String PROP_HEARTBEAT_TTL_SECONDS = "heartbeatTtlSeconds";

    private static final int DEFAULT_CONSUL_PORT = 8500;
    private static final long DEFAULT_BLOCKING_QUERY_WAIT_SECONDS = 55L;
    private static final long DEFAULT_WATCH_SHUTDOWN_TIMEOUT_MILLIS = 2000L;
    private static final long WATCH_RETRY_DELAY_MILLIS = 1000L;
    // Consul enforces a 1-minute minimum for this field; use it as the default so a configured
    // check self-cleans crashed instances instead of leaving them "critical" forever.
    private static final String DEFAULT_CHECK_DEREGISTER_CRITICAL_SERVICE_AFTER = "1m";

    private final ConsulClient client;
    private final Properties properties;
    private final String aclToken;
    private final boolean pingOnStart;
    private final long blockingQueryWaitSeconds;
    private final long watchShutdownTimeoutMillis;
    private final long heartbeatTtlSeconds;
    // Each watch loop and each TTL heartbeat runs on its own virtual thread, so the number of
    // concurrent watches is bounded only by Consul itself, not by a fixed platform-thread pool.
    private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<Long, AutoCloseable> watchHandles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> heartbeats = new ConcurrentHashMap<>();
    private final AtomicLong watchId = new AtomicLong(0);
    private final AtomicBoolean warnedNoHealthCheck = new AtomicBoolean(false);
    private volatile boolean started;
    private volatile boolean closed;

    public ConsulRegistry(String endpoint) {
        this(endpoint, new Properties());
    }

    public ConsulRegistry(String endpoint, Properties properties) {
        this(createClient(endpoint), properties);
    }

    public ConsulRegistry(ConsulClient client) {
        this(client, new Properties());
    }

    public ConsulRegistry(ConsulClient client, Properties properties) {
        if (client == null) {
            throw new RegistryOperationException("consul client must not be null");
        }
        this.client = client;
        this.properties = copyProperties(properties);
        this.aclToken = RegistryProperties.firstNonBlank(
                null,
                this.properties.getProperty(PROP_ACL_TOKEN),
                this.properties.getProperty("token"),
                this.properties.getProperty("consul.token")
        );
        this.pingOnStart = RegistryProperties.booleanValue(
                RegistryProperties.firstNonBlank("true", this.properties.getProperty(PROP_PING_ON_START)),
                true
        );
        this.blockingQueryWaitSeconds = RegistryProperties.positiveLong(
                PROP_BLOCKING_QUERY_WAIT_SECONDS,
                RegistryProperties.firstNonBlank(
                        String.valueOf(DEFAULT_BLOCKING_QUERY_WAIT_SECONDS),
                        this.properties.getProperty(PROP_BLOCKING_QUERY_WAIT_SECONDS),
                        this.properties.getProperty("consul." + PROP_BLOCKING_QUERY_WAIT_SECONDS)
                )
        );
        this.watchShutdownTimeoutMillis = positiveLongOption(
                this.properties,
                PROP_WATCH_SHUTDOWN_TIMEOUT_MILLIS,
                DEFAULT_WATCH_SHUTDOWN_TIMEOUT_MILLIS);
        this.heartbeatTtlSeconds = heartbeatTtlSeconds(this.properties);
    }

    public ConsulClient getClient() {
        return client;
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

    public long getWatchShutdownTimeoutMillis() {
        return watchShutdownTimeoutMillis;
    }

    public long getHeartbeatTtlSeconds() {
        return heartbeatTtlSeconds;
    }

    private static ConsulClient createClient(String endpoint) {
        String normalized = RegistryValidators.normalizeEndpoints(endpoint);
        if (normalized.contains(",")) {
            throw new RegistryOperationException("consul endpoints must contain exactly one agent address");
        }
        URI uri = URI.create(normalized.contains("://") ? normalized : "http://" + normalized);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RegistryOperationException("consul endpoint host must not be blank");
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            throw new RegistryOperationException("consul endpoint must not contain a path");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_CONSUL_PORT;
        return new ConsulClient(host, port);
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new RegistryOperationException("Consul registry has been closed");
        }
        if (started) {
            return;
        }
        try {
            if (pingOnStart) {
                client.getStatusLeader();
            }
            started = true;
        } catch (RuntimeException e) {
            throw new RegistryConnectionException("Failed to start consul registry", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        CloseChain chain = new CloseChain();
        for (AutoCloseable handle : new ArrayList<>(watchHandles.values())) {
            chain.step("Failed to close consul watch", handle::close);
        }
        watchHandles.clear();
        heartbeats.clear();
        taskExecutor.shutdownNow();
        chain.step("Interrupted while stopping consul task executor", () -> {
            try {
                if (!taskExecutor.awaitTermination(watchShutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    LOGGER.warn("Consul task executor did not terminate in {}ms", watchShutdownTimeoutMillis);
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
        NewService service = toConsulService(serviceInstance);
        warnIfNoHealthCheck(service);
        try {
            if (aclToken == null) {
                client.agentServiceRegister(service);
            } else {
                client.agentServiceRegister(service, aclToken);
            }
        } catch (RuntimeException e) {
            throw new RegistryOperationException("Failed to register service in consul: "
                    + serviceInstance.getName(), e);
        }
        if (heartbeatTtlSeconds > 0) {
            startHeartbeat(serviceInstance);
        }
    }

    @Override
    public void unregister(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        stopHeartbeat(consulServiceId(serviceInstance));
        try {
            if (aclToken == null) {
                client.agentServiceDeregister(consulServiceId(serviceInstance));
            } else {
                client.agentServiceDeregister(consulServiceId(serviceInstance), aclToken);
            }
        } catch (RuntimeException e) {
            throw new RegistryOperationException("Failed to unregister service in consul: "
                    + serviceInstance.getName(), e);
        }
    }

    @Override
    public Collection<ServiceInstance> getInstances(String serviceName) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        try {
            return queryInstances(serviceName, QueryParams.DEFAULT).instances();
        } catch (RuntimeException e) {
            throw new RegistryOperationException("Failed to query instances in consul: " + serviceName, e);
        }
    }

    @Override
    public Collection<String> getServiceNames() {
        assertStarted();
        try {
            return queryServiceNames(QueryParams.DEFAULT).serviceNames();
        } catch (RuntimeException e) {
            throw new RegistryOperationException("Failed to query service names in consul", e);
        }
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        RegistryValidators.validateListener(listener);
        ConcurrentMap<String, ServiceInstance> previous = new ConcurrentHashMap<>();
        InstanceSnapshot initial = queryInstances(serviceName, QueryParams.DEFAULT);
        emitInstanceChanges(serviceName, listener, previous, initial.instances());
        Future<?> future = submitWatchLoop(
                () -> watchServiceLoop(serviceName, listener, previous, initial.index()),
                "consul service watch " + serviceName);
        return registerWatchHandle(() -> future.cancel(true));
    }

    @Override
    public AutoCloseable watchServiceNames(ServiceNameListener listener) {
        assertStarted();
        RegistryValidators.validateListener(listener);
        Set<String> previous = ConcurrentHashMap.newKeySet();
        ServiceNameSnapshot initial = queryServiceNames(QueryParams.DEFAULT);
        emitServiceNameChanges(listener, previous, initial.serviceNames());
        Future<?> future = submitWatchLoop(
                () -> watchServiceNameLoop(listener, previous, initial.index()),
                "consul service-name watch");
        return registerWatchHandle(() -> future.cancel(true));
    }

    private Future<?> submitWatchLoop(Runnable task, String operation) {
        try {
            return taskExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new RegistryOperationException("Failed to schedule " + operation, e);
        }
    }

    private void watchServiceLoop(
            String serviceName,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> previous,
            long initialIndex
    ) {
        long index = initialIndex;
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                InstanceSnapshot snapshot = queryInstances(serviceName, blockingQuery(index));
                index = nextIndex(index, snapshot.index());
                emitInstanceChanges(serviceName, listener, previous, snapshot.instances());
            } catch (RuntimeException e) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    return;
                }
                LOGGER.warn("Failed to watch consul service {}", serviceName, e);
                sleepBeforeWatchRetry();
            }
        }
    }

    private void watchServiceNameLoop(ServiceNameListener listener, Set<String> previous, long initialIndex) {
        long index = initialIndex;
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                ServiceNameSnapshot snapshot = queryServiceNames(blockingQuery(index));
                index = nextIndex(index, snapshot.index());
                emitServiceNameChanges(listener, previous, snapshot.serviceNames());
            } catch (RuntimeException e) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    return;
                }
                LOGGER.warn("Failed to watch consul service names", e);
                sleepBeforeWatchRetry();
            }
        }
    }

    private void emitInstanceChanges(
            String serviceName,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> previous,
            Collection<ServiceInstance> instances
    ) {
        RegistryWatchDiff.emitInstanceChanges(
                serviceName,
                listener,
                previous,
                instances,
                LOGGER);
    }

    private void emitServiceNameChanges(ServiceNameListener listener, Set<String> previous, Collection<String> names) {
        RegistryWatchDiff.emitServiceNameChanges(
                listener,
                previous,
                names,
                LOGGER);
    }

    private InstanceSnapshot queryInstances(String serviceName, QueryParams queryParams) {
        Response<List<HealthService>> response = aclToken == null
                ? client.getHealthServices(serviceName, false, queryParams)
                : client.getHealthServices(serviceName, false, queryParams, aclToken);
        ArrayList<ServiceInstance> instances = new ArrayList<>();
        List<HealthService> services = response == null ? null : response.getValue();
        if (services != null) {
            for (HealthService healthService : services) {
                ServiceInstance instance = fromHealthService(serviceName, healthService);
                if (instance != null) {
                    instances.add(instance);
                }
            }
        }
        instances.sort(Comparator.comparing(ServiceInstance::getKey));
        return new InstanceSnapshot(
                Collections.unmodifiableList(instances),
                consulIndex(response)
        );
    }

    private ServiceNameSnapshot queryServiceNames(QueryParams queryParams) {
        Response<Map<String, List<String>>> response = aclToken == null
                ? client.getCatalogServices(queryParams)
                : client.getCatalogServices(queryParams, aclToken);
        Set<String> names = new HashSet<>();
        Map<String, List<String>> services = response == null ? null : response.getValue();
        if (services != null) {
            for (String name : services.keySet()) {
                if (RegistryValidators.isValidServiceName(LOGGER, name)) {
                    names.add(name);
                }
            }
        }
        ArrayList<String> sortedNames = new ArrayList<>(names);
        Collections.sort(sortedNames);
        return new ServiceNameSnapshot(
                Collections.unmodifiableList(sortedNames),
                consulIndex(response)
        );
    }

    private QueryParams blockingQuery(long index) {
        return new QueryParams(blockingQueryWaitSeconds, index);
    }

    private static long consulIndex(Response<?> response) {
        Long index = response == null ? null : response.getConsulIndex();
        return index == null || index < 0 ? 0L : index;
    }

    private static long nextIndex(long current, long next) {
        return next > 0 ? next : current;
    }

    private void sleepBeforeWatchRetry() {
        try {
            TimeUnit.MILLISECONDS.sleep(WATCH_RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AutoCloseable registerWatchHandle(AutoCloseable closeable) {
        return RegistryWatchHandles.register(
                watchHandles,
                watchId,
                this,
                () -> closed,
                closeable,
                "Consul registry has been closed",
                "Failed to close consul watch after registry closed");
    }

    private NewService toConsulService(ServiceInstance serviceInstance) {
        NewService service = new NewService();
        service.setId(consulServiceId(serviceInstance));
        service.setName(serviceInstance.getName());
        service.setAddress(serviceInstance.getAddress());
        service.setPort(serviceInstance.getPort());
        List<String> tags = consulTags(serviceInstance.getMetadata());
        if (!tags.isEmpty()) {
            service.setTags(tags);
        }
        service.setMeta(toConsulMeta(serviceInstance));
        NewService.Check check = newCheck();
        if (check != null) {
            service.setCheck(check);
        }
        return service;
    }

    private Map<String, String> toConsulMeta(ServiceInstance serviceInstance) {
        Map<String, String> metadata = new HashMap<>();
        if (serviceInstance.getMetadata() != null) {
            metadata.putAll(serviceInstance.getMetadata());
        }
        metadata.put(META_ID, serviceInstance.getId() == null ? "" : serviceInstance.getId());
        metadata.put(META_WEIGHT, String.valueOf(serviceInstance.getWeight()));
        metadata.put(META_HEALTHY, String.valueOf(serviceInstance.isHealthy()));
        metadata.put(META_REGISTRATION_TIME, String.valueOf(serviceInstance.getRegistrationTimeUTC()));
        return metadata;
    }

    private String consulServiceId(ServiceInstance serviceInstance) {
        return serviceInstance.getName() + CONSUL_SERVICE_ID_SEPARATOR + serviceInstance.getKey();
    }

    private List<String> consulTags(Map<String, String> metadata) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addTags(tags, properties.getProperty(PROP_TAGS));
        addTags(tags, properties.getProperty("consul.tags"));
        if (metadata != null) {
            addTags(tags, metadata.get(META_TAGS));
        }
        return new ArrayList<>(tags);
    }

    private static void addTags(Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String tag : raw.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    private NewService.Check newCheck() {
        NewService.Check check = new NewService.Check();
        boolean configured = false;
        configured |= applyString(PROP_CHECK_HTTP, check::setHttp);
        configured |= applyString(PROP_CHECK_TCP, check::setTcp);
        configured |= applyString(PROP_CHECK_INTERVAL, check::setInterval);
        configured |= applyString(PROP_CHECK_TIMEOUT, check::setTimeout);
        boolean deregisterConfigured = applyString(PROP_CHECK_DEREGISTER_CRITICAL_SERVICE_AFTER,
                check::setDeregisterCriticalServiceAfter);
        configured |= deregisterConfigured;
        configured |= applyString(PROP_CHECK_METHOD, check::setMethod);
        configured |= applyString(PROP_CHECK_STATUS, check::setStatus);
        String tlsSkipVerify = RegistryProperties.get(properties, PROP_CHECK_TLS_SKIP_VERIFY);
        if (tlsSkipVerify != null) {
            check.setTlsSkipVerify(RegistryProperties.booleanValue(tlsSkipVerify, false));
            configured = true;
        }
        if (!configured) {
            return null;
        }
        if (!deregisterConfigured) {
            check.setDeregisterCriticalServiceAfter(DEFAULT_CHECK_DEREGISTER_CRITICAL_SERVICE_AFTER);
        }
        return check;
    }

    private void warnIfNoHealthCheck(NewService service) {
        if (service.getCheck() == null && heartbeatTtlSeconds <= 0 && warnedNoHealthCheck.compareAndSet(false, true)) {
            LOGGER.warn("Consul services are being registered without a health check; crashed instances will not be"
                    + " automatically deregistered. Set heartbeatTtlSeconds for a registry-managed TTL check, configure"
                    + " checkHttp/checkTcp, or register via the native Consul SDK if you need dead instances removed.");
        }
    }

    private boolean applyString(String key, java.util.function.Consumer<String> consumer) {
        String value = RegistryProperties.firstNonBlank(
                null,
                properties.getProperty(key),
                properties.getProperty("consul." + key)
        );
        if (value == null) {
            return false;
        }
        consumer.accept(value);
        return true;
    }

    // Registers a standalone TTL check bound to the service and keeps it passing from a virtual
    // thread. If this process crashes the heartbeat stops, the TTL lapses, the check goes critical,
    // and Consul deregisters the service after deregisterCriticalServiceAfter -- giving Consul the
    // same crash-removal behavior the lease/ephemeral backends have.
    private void startHeartbeat(ServiceInstance serviceInstance) {
        String serviceId = consulServiceId(serviceInstance);
        String checkId = ttlCheckId(serviceId);
        NewCheck check = new NewCheck();
        check.setId(checkId);
        check.setName(serviceInstance.getName() + " ttl");
        check.setServiceId(serviceId);
        check.setTtl(heartbeatTtlSeconds + "s");
        check.setDeregisterCriticalServiceAfter(DEFAULT_CHECK_DEREGISTER_CRITICAL_SERVICE_AFTER);
        try {
            agentCheckRegister(check);
            agentCheckPass(checkId);
        } catch (RuntimeException e) {
            throw new RegistryOperationException(
                    "Failed to register consul TTL check for service: " + serviceInstance.getName(), e);
        }
        long intervalMillis = Math.max(1L, heartbeatTtlSeconds * 1000L / 3L);
        Future<?> future;
        try {
            future = taskExecutor.submit(() -> heartbeatLoop(checkId, intervalMillis));
        } catch (RejectedExecutionException e) {
            throw new RegistryOperationException("Consul registry has been closed", e);
        }
        Future<?> previous = heartbeats.put(serviceId, future);
        if (previous != null) {
            previous.cancel(true);
        }
        if (closed && heartbeats.remove(serviceId, future)) {
            future.cancel(true);
        }
    }

    private void heartbeatLoop(String checkId, long intervalMillis) {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                TimeUnit.MILLISECONDS.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                agentCheckPass(checkId);
            } catch (RuntimeException e) {
                if (!closed) {
                    LOGGER.warn("Failed to refresh consul TTL check {}", checkId, e);
                }
            }
        }
    }

    private void stopHeartbeat(String serviceId) {
        Future<?> future = heartbeats.remove(serviceId);
        if (future != null) {
            future.cancel(true);
        }
        if (heartbeatTtlSeconds > 0) {
            try {
                agentCheckDeregister(ttlCheckId(serviceId));
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to deregister consul TTL check for {}", serviceId, e);
            }
        }
    }

    private String ttlCheckId(String serviceId) {
        return serviceId + ":ttl";
    }

    private void agentCheckRegister(NewCheck check) {
        if (aclToken == null) {
            client.agentCheckRegister(check);
        } else {
            client.agentCheckRegister(check, aclToken);
        }
    }

    private void agentCheckPass(String checkId) {
        if (aclToken == null) {
            client.agentCheckPass(checkId);
        } else {
            client.agentCheckPass(checkId, null, aclToken);
        }
    }

    private void agentCheckDeregister(String checkId) {
        if (aclToken == null) {
            client.agentCheckDeregister(checkId);
        } else {
            client.agentCheckDeregister(checkId, aclToken);
        }
    }

    private ServiceInstance fromHealthService(String requestedServiceName, HealthService healthService) {
        if (healthService == null || healthService.getService() == null) {
            return null;
        }
        HealthService.Service service = healthService.getService();
        String serviceName = RegistryProperties.firstNonBlank(requestedServiceName, service.getService());
        String address = RegistryProperties.firstNonBlank(null, service.getAddress());
        if (address == null && healthService.getNode() != null) {
            address = RegistryProperties.firstNonBlank(null, healthService.getNode().getAddress());
        }
        Integer port = service.getPort();
        if (address == null || port == null || port <= 0 || port > 65535) {
            LOGGER.warn("Ignoring invalid consul service instance {}:{} for {}", address, port, serviceName);
            return null;
        }
        Map<String, String> metadata = RegistryProperties.copyStringMap(service.getMeta());
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId(originalInstanceId(serviceName, service.getId(), metadata));
        instance.setAddress(address);
        instance.setPort(port);
        String tags = joinTags(service.getTags());
        if (tags != null) {
            metadata.put(META_TAGS, tags);
        }
        instance.setMetadata(metadata);
        instance.setWeight(parseDouble(metadata.get(META_WEIGHT), 1.0D));
        instance.setHealthy(isHealthy(healthService) && parseBoolean(metadata.get(META_HEALTHY), true));
        instance.setRegistrationTimeUTC(parseLong(metadata.get(META_REGISTRATION_TIME), 0L));
        try {
            RegistryValidators.validateInstance(instance);
            return instance;
        } catch (RegistryOperationException e) {
            LOGGER.warn("Ignoring invalid consul service instance {}", instance, e);
            return null;
        }
    }

    private String originalInstanceId(String serviceName, String consulServiceId, Map<String, String> metadata) {
        if (metadata.containsKey(META_ID)) {
            return metadata.get(META_ID);
        }
        String fallback = RegistryProperties.firstNonBlank(null, consulServiceId);
        if (fallback == null) {
            return null;
        }
        String prefix = serviceName + CONSUL_SERVICE_ID_SEPARATOR;
        if (fallback.startsWith(prefix) && fallback.length() > prefix.length()) {
            return fallback.substring(prefix.length());
        }
        return fallback;
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> copied = new ArrayList<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                copied.add(tag.trim());
            }
        }
        return copied.isEmpty() ? null : String.join(",", copied);
    }

    private boolean isHealthy(HealthService healthService) {
        List<Check> checks = healthService.getChecks();
        if (checks == null || checks.isEmpty()) {
            return true;
        }
        for (Check check : checks) {
            if (check != null && isCritical(check.getStatus())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCritical(Check.CheckStatus status) {
        return status != null && "critical".equalsIgnoreCase(status.name());
    }

    private static double parseDouble(String value, double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) && parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    private void assertStarted() {
        if (closed) {
            throw new RegistryOperationException("Consul registry has been closed");
        }
        if (!started) {
            throw new RegistryOperationException("Consul registry has not been started");
        }
    }

    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            source.forEach((key, value) -> RegistryProperties.putString(copy, key, value));
        }
        return copy;
    }

    private static long heartbeatTtlSeconds(Properties properties) {
        String value = RegistryProperties.firstNonBlank(
                null,
                properties.getProperty(PROP_HEARTBEAT_TTL_SECONDS),
                properties.getProperty("consul." + PROP_HEARTBEAT_TTL_SECONDS));
        return value == null ? 0L : RegistryProperties.positiveLong(PROP_HEARTBEAT_TTL_SECONDS, value);
    }

    private static long positiveLongOption(Properties properties, String key, long defaultValue) {
        return RegistryProperties.positiveLong(
                key,
                RegistryProperties.firstNonBlank(
                        String.valueOf(defaultValue),
                        properties.getProperty(key),
                        properties.getProperty("consul." + key)
                )
        );
    }

    private record InstanceSnapshot(Collection<ServiceInstance> instances, long index) {
    }

    private record ServiceNameSnapshot(Collection<String> serviceNames, long index) {
    }
}
