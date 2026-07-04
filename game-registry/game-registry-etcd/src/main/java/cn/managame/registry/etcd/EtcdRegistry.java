package cn.managame.registry.etcd;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import cn.managame.registry.exception.RegistrySerializationException;
import cn.managame.registry.support.CloseChain;
import cn.managame.registry.support.RegistryListeners;
import cn.managame.registry.support.RegistryWatchDiff;
import cn.managame.registry.support.RegistryProperties;
import cn.managame.registry.support.RegistryValidators;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.watch.WatchEvent;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class EtcdRegistry implements Registry, Discovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(EtcdRegistry.class);
    public static final String PROP_USER = "user";
    public static final String PROP_PASSWORD = "password";
    public static final String PROP_NAMESPACE = "namespace";
    public static final String PROP_AUTHORITY = "authority";
    public static final String PROP_TRUSTED_CERT_PATH = "trustedCertPath";
    public static final String PROP_CLIENT_CERT_PATH = "clientCertPath";
    public static final String PROP_CLIENT_KEY_PATH = "clientKeyPath";
    public static final String PROP_CONNECT_TIMEOUT_MILLIS = "connectTimeoutMillis";
    public static final String PROP_KEEPALIVE_TIME_MILLIS = "keepaliveTimeMillis";
    public static final String PROP_KEEPALIVE_TIMEOUT_MILLIS = "keepaliveTimeoutMillis";
    public static final String PROP_KEEPALIVE_WITHOUT_CALLS = "keepaliveWithoutCalls";
    public static final String PROP_RETRY_DELAY_MILLIS = "retryDelayMillis";
    public static final String PROP_RETRY_MAX_DELAY_MILLIS = "retryMaxDelayMillis";
    public static final String PROP_RETRY_MAX_ATTEMPTS = "retryMaxAttempts";
    public static final String PROP_MAX_INBOUND_MESSAGE_SIZE = "maxInboundMessageSize";
    public static final String PROP_OPERATION_TIMEOUT_MILLIS = "operationTimeoutMillis";

    private static final long INITIAL_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000;
    private static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 5000;
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;

    private final Client client;
    private volatile KV kvClient;
    private volatile Lease leaseClient;
    private volatile Watch watchClient;
    private final String basePath;
    private final ObjectMapper objectMapper;
    private final AtomicLong leaseId = new AtomicLong(0);
    private final long ttlSeconds;
    private final long operationTimeoutMillis;
    private volatile boolean started;
    private volatile boolean starting;
    private final ConcurrentMap<Long, WatchRegistration> watchHandles = new ConcurrentHashMap<>();
    private final AtomicLong watchId = new AtomicLong(0);
    private final ConcurrentMap<String, ServiceInstance> registeredInstances = new ConcurrentHashMap<>();
    // Lease renewal and watch reconnection run on separate executors. A watch
    // reconnect performs a blocking snapshot refresh (a remote get bounded by
    // operationTimeoutMillis); if it shared a thread with lease keep-alive
    // reconnection it could delay lease renewal long enough for this process's
    // instances to expire from etcd. Keeping them apart protects the lease.
    private final ScheduledExecutorService leaseRetryExecutor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    private final ScheduledExecutorService watchRetryExecutor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private volatile long currentRetryDelayMs = INITIAL_RETRY_DELAY_MS;
    private volatile boolean closed;
    private CloseableClient keepAliveClient;

    private static ThreadFactory daemonThreadFactory() {
        AtomicLong threadId = new AtomicLong(0);
        return task -> {
            Thread thread = new Thread(task, "game-registry-etcd-retry-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public EtcdRegistry(String endpoints, String basePath) {
        this(endpoints, basePath, 10);
    }

    public EtcdRegistry(String endpoints, String basePath, long ttlSeconds) {
        this(endpoints, basePath, ttlSeconds, new Properties());
    }

    public EtcdRegistry(String endpoints, String basePath, long ttlSeconds, Properties properties) {
        String normalizedEndpoints = RegistryValidators.normalizeEndpoints(endpoints);
        String normalizedBasePath = RegistryValidators.normalizeBasePath(basePath);
        RegistryValidators.validateLeaseTtlSeconds(ttlSeconds);
        Properties options = properties == null ? new Properties() : properties;
        this.client = buildClient(normalizedEndpoints, options);
        this.basePath = normalizedBasePath.endsWith("/") ? normalizedBasePath : normalizedBasePath + "/";
        // During rolling upgrades, older instance JSON may contain fields this
        // node does not know yet. Reads and watch callbacks must tolerate them.
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.ttlSeconds = ttlSeconds;
        this.operationTimeoutMillis = RegistryProperties.positiveLong(
                options,
                PROP_OPERATION_TIMEOUT_MILLIS,
                DEFAULT_OPERATION_TIMEOUT_MILLIS);
    }

    private Client buildClient(String endpoints, Properties properties) {
        try {
            var builder = Client.builder().endpoints(endpoints.split(","));
            applyByteSequence(properties, PROP_USER, builder::user);
            applyByteSequence(properties, PROP_PASSWORD, builder::password);
            applyByteSequence(properties, PROP_NAMESPACE, builder::namespace);
            RegistryProperties.applyString(properties, PROP_AUTHORITY, builder::authority);
            RegistryProperties.applyDurationMillis(properties, PROP_CONNECT_TIMEOUT_MILLIS, builder::connectTimeout);
            RegistryProperties.applyDurationMillis(properties, PROP_KEEPALIVE_TIME_MILLIS, builder::keepaliveTime);
            RegistryProperties.applyDurationMillis(properties, PROP_KEEPALIVE_TIMEOUT_MILLIS, builder::keepaliveTimeout);
            RegistryProperties.applyPositiveLong(properties, PROP_RETRY_DELAY_MILLIS, builder::retryDelay);
            RegistryProperties.applyPositiveLong(properties, PROP_RETRY_MAX_DELAY_MILLIS, builder::retryMaxDelay);
            RegistryProperties.applyPositiveInt(properties, PROP_RETRY_MAX_ATTEMPTS, builder::retryMaxAttempts);
            RegistryProperties.applyPositiveInt(properties, PROP_MAX_INBOUND_MESSAGE_SIZE, builder::maxInboundMessageSize);
            String keepaliveWithoutCalls = RegistryProperties.get(properties, PROP_KEEPALIVE_WITHOUT_CALLS);
            if (keepaliveWithoutCalls != null) {
                builder.keepaliveWithoutCalls(RegistryProperties.booleanValue(keepaliveWithoutCalls, false));
            }
            configureTls(properties, builder);
            return builder.build();
        } catch (RegistryOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to create etcd client", e);
        }
    }

    private void configureTls(Properties properties, io.etcd.jetcd.ClientBuilder builder) throws Exception {
        String trustedCertPath = RegistryProperties.get(properties, PROP_TRUSTED_CERT_PATH);
        String clientCertPath = RegistryProperties.get(properties, PROP_CLIENT_CERT_PATH);
        String clientKeyPath = RegistryProperties.get(properties, PROP_CLIENT_KEY_PATH);
        if (trustedCertPath == null && clientCertPath == null && clientKeyPath == null) {
            return;
        }
        SslContextBuilder ssl = SslContextBuilder.forClient();
        if (trustedCertPath != null) {
            ssl.trustManager(new File(trustedCertPath));
        }
        if (clientCertPath != null || clientKeyPath != null) {
            if (clientCertPath == null || clientKeyPath == null) {
                throw new RegistryOperationException("clientCertPath and clientKeyPath must be set together");
            }
            ssl.keyManager(new File(clientCertPath), new File(clientKeyPath));
        }
        builder.sslContext(ssl.build());
    }

    private void applyByteSequence(Properties properties, String key, Consumer<ByteSequence> consumer) {
        String value = RegistryProperties.get(properties, key);
        if (value != null) {
            consumer.accept(bytesOf(value));
        }
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isClosed() {
        return closed;
    }

    public int getRegisteredInstanceCount() {
        return registeredInstances.size();
    }

    public int getActiveWatchCount() {
        return watchHandles.size();
    }

    public long getCurrentLeaseId() {
        return leaseId.get();
    }

    public boolean isReconnectScheduled() {
        return reconnectScheduled.get();
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new RegistryOperationException("Etcd registry has been closed");
        }
        if (started) {
            return;
        }
        starting = true;
        try {
            long grantedLeaseId = await(leaseClient().grant(ttlSeconds), "grant etcd lease").getID();
            leaseId.set(grantedLeaseId);
            currentRetryDelayMs = INITIAL_RETRY_DELAY_MS;
            startKeepAlive();
            started = true;
        } catch (Exception e) {
            started = false;
            closeKeepAlive();
            long revokeLeaseId = leaseId.getAndSet(0);
            if (revokeLeaseId != 0) {
                try {
                    await(leaseClient().revoke(revokeLeaseId), "revoke etcd lease after start failure");
                } catch (Exception revokeFailure) {
                    e.addSuppressed(revokeFailure);
                }
            }
            throw new RegistryConnectionException("Failed to start etcd registry", e);
        } finally {
            starting = false;
        }
    }

    private synchronized void startKeepAlive() {
        long currentLeaseId = leaseId.get();
        if (currentLeaseId == 0)
            return;
        closeKeepAlive();
        keepAliveClient = leaseClient().keepAlive(currentLeaseId, new StreamObserver<io.etcd.jetcd.lease.LeaseKeepAliveResponse>() {
            @Override
            public void onNext(io.etcd.jetcd.lease.LeaseKeepAliveResponse value) {
                currentRetryDelayMs = INITIAL_RETRY_DELAY_MS;
            }

            @Override
            public void onError(Throwable t) {
                scheduleReconnection();
            }

            @Override
            public void onCompleted() {
                scheduleReconnection();
            }
        });
    }

    private void scheduleReconnection() {
        if ((!started && !starting) || closed) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = currentRetryDelayMs;
        currentRetryDelayMs = Math.min((long) (currentRetryDelayMs * RETRY_BACKOFF_MULTIPLIER), MAX_RETRY_DELAY_MS);
        leaseRetryExecutor.schedule(() -> {
            boolean retry = false;
            try {
                synchronized (EtcdRegistry.this) {
                    if (!started || closed) {
                        return;
                    }
                    leaseId.set(await(leaseClient().grant(ttlSeconds), "grant etcd lease").getID());
                    currentRetryDelayMs = INITIAL_RETRY_DELAY_MS;
                    startKeepAlive();
                }
                for (ServiceInstance instance : registeredInstances.values()) {
                    doRegister(instance);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to reconnect etcd lease, scheduling another retry", e);
                retry = true;
            } finally {
                reconnectScheduled.set(false);
                if (retry) {
                    scheduleReconnection();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        CloseChain chain = new CloseChain();
        leaseRetryExecutor.shutdownNow();
        watchRetryExecutor.shutdownNow();
        awaitRetryExecutorTermination(leaseRetryExecutor, "lease");
        awaitRetryExecutorTermination(watchRetryExecutor, "watch");
        chain.step("Failed to close etcd keepalive", this::closeKeepAlive);
        for (WatchRegistration watch : new ArrayList<>(watchHandles.values())) {
            chain.step("Failed to close etcd watcher", watch::close);
        }
        watchHandles.clear();
        registeredInstances.clear();
        long revokeLeaseId = leaseId.getAndSet(0);
        Lease currentLeaseClient = leaseClient;
        if (revokeLeaseId != 0 && currentLeaseClient != null) {
            chain.step("Failed to revoke etcd lease",
                    () -> await(currentLeaseClient.revoke(revokeLeaseId), "revoke etcd lease"));
        }
        chain.step("Failed to close etcd client", client::close);
        chain.throwIfFailed();
    }

    private void awaitRetryExecutorTermination(ScheduledExecutorService executor, String name) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Etcd {} retry executor did not terminate in 5s; leaving background tasks to expire naturally",
                        name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for etcd {} retry executor to terminate", name, e);
        }
    }

    private synchronized void closeKeepAlive() {
        if (keepAliveClient != null) {
            keepAliveClient.close();
            keepAliveClient = null;
        }
    }

    private <T> T await(CompletableFuture<T> future, String operation) throws Exception {
        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RegistryOperationException(
                    operation + " timed out after " + operationTimeoutMillis + "ms", e);
        }
    }

    @Override
    public void register(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        ServiceInstance copy = serviceInstance.copy();
        try {
            doRegister(copy);
            registeredInstances.put(registrationKey(copy), copy);
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to register service in etcd: " + serviceInstance.getName(), e);
        }
    }

    private void doRegister(ServiceInstance serviceInstance) throws Exception {
        long currentLeaseId = leaseId.get();
        if (currentLeaseId == 0) {
            throw new RegistryOperationException("Etcd registry lease is not available");
        }
        String key = buildKey(serviceInstance);
        String value = objectMapper.writeValueAsString(serviceInstance);
        await(kvClient().put(
                bytesOf(key),
                bytesOf(value),
                PutOption.builder().withLeaseId(currentLeaseId).build()), "put etcd service instance");
    }

    @Override
    public void unregister(ServiceInstance serviceInstance) {
        assertStarted();
        RegistryValidators.validateInstance(serviceInstance);
        try {
            await(kvClient().delete(bytesOf(buildKey(serviceInstance))), "delete etcd service instance");
            registeredInstances.remove(registrationKey(serviceInstance));
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to unregister service in etcd: " + serviceInstance.getName(), e);
        }
    }

    @Override
    public Collection<ServiceInstance> getInstances(String serviceName) {
        assertStarted();
        RegistryValidators.validateServiceName(serviceName);
        String keyPrefix = basePath + serviceName + "/";
        GetResponse response;
        try {
            response = await(kvClient().get(bytesOf(keyPrefix), GetOption.builder().isPrefix(true).build()),
                    "query etcd instances");
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to query instances in etcd: " + serviceName, e);
        }
        List<ServiceInstance> instances = new ArrayList<>();
        for (KeyValue kv : response.getKvs()) {
            try {
                String key = kv.getKey().toString(StandardCharsets.UTF_8);
                ServiceInstance instance = deserializeInstance(kv, serviceName, key);
                instances.add(instance);
            } catch (Exception e) {
                throw new RegistrySerializationException("Failed to deserialize service instance from etcd", e);
            }
        }
        instances.sort(Comparator.comparing(ServiceInstance::getKey));
        return Collections.unmodifiableList(instances);
    }

    @Override
    public Collection<String> getServiceNames() {
        assertStarted();
        GetResponse response;
        try {
            response = await(kvClient().get(bytesOf(basePath), GetOption.builder().isPrefix(true).build()),
                    "query etcd service names");
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to query service names in etcd", e);
        }
        Set<String> names = new LinkedHashSet<>();
        for (KeyValue kv : response.getKvs()) {
            String key = kv.getKey().toString(StandardCharsets.UTF_8);
            String serviceName = parseServiceName(key);
            if (RegistryValidators.isValidServiceName(LOGGER, serviceName)) {
                names.add(serviceName);
            }
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
        WatchRegistration registration = WatchRegistration.forService(
                watchId.incrementAndGet(),
                serviceName,
                listener,
                known
        );
        registerWatchRegistration(registration);
        try {
            assignInitialWatcher(registration, openServiceWatcher(registration));
            refreshServiceWatchSnapshot(registration);
        } catch (RuntimeException e) {
            watchHandles.remove(registration.id);
            registration.close();
            throw e;
        }
        return closeableWatcher(registration.id);
    }

    @Override
    public AutoCloseable watchServiceNames(ServiceNameListener listener) {
        assertStarted();
        RegistryValidators.validateListener(listener);
        WatchRegistration registration = WatchRegistration.forServiceNames(
                watchId.incrementAndGet(),
                listener
        );
        registerWatchRegistration(registration);
        try {
            assignInitialWatcher(registration, openServiceNameWatcher(registration));
            refreshServiceNameWatchSnapshot(registration);
        } catch (RuntimeException e) {
            watchHandles.remove(registration.id);
            registration.close();
            throw e;
        }
        return closeableWatcher(registration.id);
    }

    private AutoCloseable closeableWatcher(long id) {
        return () -> {
            WatchRegistration removed = watchHandles.remove(id);
            if (removed != null) {
                removed.close();
            }
        };
    }

    private void registerWatchRegistration(WatchRegistration registration) {
        synchronized (this) {
            if (closed) {
                throw new RegistryOperationException("Etcd registry has been closed");
            }
            if (!started) {
                throw new RegistryOperationException("Etcd registry has not been started");
            }
            watchHandles.put(registration.id, registration);
        }
    }

    private void assignInitialWatcher(WatchRegistration registration, Watch.Watcher watcher) {
        synchronized (this) {
            if (!closed && started && !registration.closed && watchHandles.get(registration.id) == registration) {
                registration.watcher = watcher;
                return;
            }
        }
        if (watcher != null) {
            watcher.close();
        }
        throw new RegistryOperationException("Etcd registry has been closed");
    }

    private Watch.Watcher openServiceWatcher(WatchRegistration registration) {
        String serviceName = registration.serviceName;
        String keyPrefix = basePath + serviceName + "/";
        return watchClient().watch(
                bytesOf(keyPrefix),
                WatchOption.builder().isPrefix(true).withPrevKV(true).build(),
                Watch.listener(
                        response -> handleServiceWatchResponse(registration, response.getEvents()),
                        throwable -> scheduleWatchReconnect(registration, throwable),
                        () -> scheduleWatchReconnect(registration, null)
                )
        );
    }

    private void handleServiceWatchResponse(WatchRegistration registration, List<WatchEvent> events) {
        if (!isWatchActive(registration)) {
            return;
        }
        String serviceName = registration.serviceName;
        for (WatchEvent event : events) {
            if (!isWatchActive(registration)) {
                return;
            }
            try {
                String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
                if (event.getEventType() == WatchEvent.EventType.PUT) {
                    ServiceInstance latest = deserializeInstance(event.getKeyValue(), serviceName, key);
                    ServiceInstance old = registration.knownInstances.put(latest.getKey(), latest);
                    if (old == null) {
                        RegistryListeners.notify(LOGGER,
                                registration.instanceListener,
                                new ServiceInstanceEvent(serviceName, DiscoveryEventType.ADDED, latest)
                        );
                    } else if (!old.equals(latest)) {
                        RegistryListeners.notify(LOGGER,
                                registration.instanceListener,
                                new ServiceInstanceEvent(serviceName, DiscoveryEventType.UPDATED, latest)
                        );
                    }
                } else if (event.getEventType() == WatchEvent.EventType.DELETE) {
                    String instanceId = parseInstanceId(key);
                    ServiceInstance removed = registration.knownInstances.remove(instanceId);
                    if (removed == null && event.getPrevKV() != null && !event.getPrevKV().getValue().isEmpty()) {
                        removed = deserializeInstance(event.getPrevKV(), serviceName, key);
                    }
                    if (removed != null) {
                        RegistryListeners.notify(LOGGER,
                                registration.instanceListener,
                                new ServiceInstanceEvent(serviceName, DiscoveryEventType.REMOVED, removed)
                        );
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to handle etcd watch event for service {}", serviceName, e);
            }
        }
    }

    private Watch.Watcher openServiceNameWatcher(WatchRegistration registration) {
        return watchClient().watch(
                bytesOf(basePath),
                WatchOption.builder().isPrefix(true).withPrevKV(true).build(),
                Watch.listener(
                        response -> handleServiceNameWatchResponse(registration, response.getEvents()),
                        throwable -> scheduleWatchReconnect(registration, throwable),
                        () -> scheduleWatchReconnect(registration, null)
                )
        );
    }

    private void handleServiceNameWatchResponse(WatchRegistration registration, List<WatchEvent> events) {
        if (!isWatchActive(registration)) {
            return;
        }
        for (WatchEvent event : events) {
            if (!isWatchActive(registration)) {
                return;
            }
            try {
                String key = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
                String serviceName = parseServiceName(key);
                if (!RegistryValidators.isValidServiceName(LOGGER, serviceName)) {
                    continue;
                }
                // jetcd dispatches events for one watcher serially here, so the
                // local map can be updated directly. Track per-service instances
                // locally to detect the last removed instance without doing etcd
                // RPCs from the watch callback thread.
                String instanceId = parseInstanceId(key);
                if (event.getEventType() == WatchEvent.EventType.PUT) {
                    Set<String> instances = registration.serviceNameInstances
                            .computeIfAbsent(serviceName, k -> ConcurrentHashMap.newKeySet());
                    boolean firstInstance = instances.isEmpty();
                    instances.add(instanceId);
                    if (firstInstance) {
                        RegistryListeners.notify(LOGGER,
                                registration.serviceNameListener,
                                new ServiceNameEvent(serviceName, DiscoveryEventType.ADDED)
                        );
                    }
                } else if (event.getEventType() == WatchEvent.EventType.DELETE) {
                    Set<String> instances = registration.serviceNameInstances.get(serviceName);
                    if (instances != null) {
                        instances.remove(instanceId);
                        if (instances.isEmpty()) {
                            registration.serviceNameInstances.remove(serviceName);
                            RegistryListeners.notify(LOGGER,
                                    registration.serviceNameListener,
                                    new ServiceNameEvent(serviceName, DiscoveryEventType.REMOVED)
                            );
                        }
                    }
                    // No local entry means the event landed during a reconnect
                    // gap; the next snapshot refresh reconciles the difference.
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to handle etcd service-name watch event", e);
            }
        }
    }

    private void scheduleWatchReconnect(WatchRegistration registration, Throwable cause) {
        if (!isWatchActive(registration)) {
            return;
        }
        if (!registration.reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        if (cause == null) {
            LOGGER.warn("Etcd watch completed, scheduling reconnect");
        } else {
            LOGGER.warn("Etcd watch failed, scheduling reconnect", cause);
        }
        long delay = registration.currentRetryDelayMs;
        registration.currentRetryDelayMs = Math.min(
                (long) (registration.currentRetryDelayMs * RETRY_BACKOFF_MULTIPLIER),
                MAX_RETRY_DELAY_MS
        );
        try {
            watchRetryExecutor.schedule(() -> reconnectWatch(registration), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            registration.reconnectScheduled.set(false);
            if (!closed && !registration.closed) {
                throw e;
            }
        }
    }

    private void reconnectWatch(WatchRegistration registration) {
        boolean retry = false;
        try {
            if (!isWatchActive(registration)) {
                return;
            }
            if (registration.serviceNameWatch) {
                replaceWatcher(registration, openServiceNameWatcher(registration));
                refreshServiceNameWatchSnapshot(registration);
            } else {
                replaceWatcher(registration, openServiceWatcher(registration));
                refreshServiceWatchSnapshot(registration);
            }
            registration.currentRetryDelayMs = INITIAL_RETRY_DELAY_MS;
        } catch (Exception e) {
            LOGGER.warn("Failed to reconnect etcd watch, scheduling another retry", e);
            retry = true;
        } finally {
            registration.reconnectScheduled.set(false);
            if (retry) {
                scheduleWatchReconnect(registration, null);
            }
        }
    }

    private void replaceWatcher(WatchRegistration registration, Watch.Watcher next) {
        Watch.Watcher previous;
        synchronized (this) {
            if (!isWatchActive(registration)) {
                if (next != null) {
                    next.close();
                }
                return;
            }
            previous = registration.watcher;
            registration.watcher = next;
        }
        if (previous != null) {
            previous.close();
        }
    }

    private boolean isWatchActive(WatchRegistration registration) {
        return registration != null
                && !closed
                && started
                && !registration.closed
                && watchHandles.get(registration.id) == registration;
    }

    private void refreshServiceWatchSnapshot(WatchRegistration registration) {
        String serviceName = registration.serviceName;
        RegistryWatchDiff.emitInstanceChanges(
                serviceName,
                registration.instanceListener,
                registration.knownInstances,
                getInstances(serviceName),
                LOGGER);
    }

    private void refreshServiceNameWatchSnapshot(WatchRegistration registration) {
        // One basePath prefix scan rebuilds serviceNameInstances; diffing the old
        // and new key sets yields ADDED / REMOVED events.
        ConcurrentMap<String, Set<String>> rebuilt = new ConcurrentHashMap<>();
        GetResponse response;
        try {
            response = await(kvClient().get(bytesOf(basePath), GetOption.builder().isPrefix(true).build()),
                    "refresh etcd service-name watch snapshot");
        } catch (Exception e) {
            throw new RegistryOperationException("Failed to query service names in etcd", e);
        }
        for (KeyValue kv : response.getKvs()) {
            String key = kv.getKey().toString(StandardCharsets.UTF_8);
            String serviceName = parseServiceName(key);
            if (!RegistryValidators.isValidServiceName(LOGGER, serviceName)) {
                continue;
            }
            rebuilt.computeIfAbsent(serviceName, k -> ConcurrentHashMap.newKeySet())
                    .add(parseInstanceId(key));
        }
        Set<String> previous = new java.util.HashSet<>(registration.serviceNameInstances.keySet());
        registration.serviceNameInstances.clear();
        registration.serviceNameInstances.putAll(rebuilt);
        for (String serviceName : rebuilt.keySet()) {
            if (!previous.contains(serviceName)) {
                RegistryListeners.notify(LOGGER,
                        registration.serviceNameListener,
                        new ServiceNameEvent(serviceName, DiscoveryEventType.ADDED)
                );
            }
        }
        for (String serviceName : previous) {
            if (!rebuilt.containsKey(serviceName)) {
                RegistryListeners.notify(LOGGER,
                        registration.serviceNameListener,
                        new ServiceNameEvent(serviceName, DiscoveryEventType.REMOVED)
                );
            }
        }
    }

    private void assertStarted() {
        if (closed) {
            throw new RegistryOperationException("Etcd registry has been closed");
        }
        if (!started) {
            throw new RegistryOperationException("Etcd registry has not been started");
        }
    }

    private String buildKey(ServiceInstance instance) {
        return basePath + instance.getName() + "/" + instance.getKey();
    }

    private String registrationKey(ServiceInstance instance) {
        return buildKey(instance);
    }

    private KV kvClient() {
        KV current = kvClient;
        if (current == null) {
            synchronized (this) {
                current = kvClient;
                if (current == null) {
                    current = client.getKVClient();
                    kvClient = current;
                }
            }
        }
        return current;
    }

    private Lease leaseClient() {
        Lease current = leaseClient;
        if (current == null) {
            synchronized (this) {
                current = leaseClient;
                if (current == null) {
                    current = client.getLeaseClient();
                    leaseClient = current;
                }
            }
        }
        return current;
    }

    private Watch watchClient() {
        Watch current = watchClient;
        if (current == null) {
            synchronized (this) {
                current = watchClient;
                if (current == null) {
                    current = client.getWatchClient();
                    watchClient = current;
                }
            }
        }
        return current;
    }

    private ByteSequence bytesOf(String val) {
        return ByteSequence.from(val, StandardCharsets.UTF_8);
    }

    private ServiceInstance deserializeInstance(KeyValue keyValue, String serviceName, String key) throws Exception {
        String json = keyValue.getValue().toString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(json);
        boolean hasIdField = root.has("id");
        sanitizeMetadata(root);
        ServiceInstance instance = objectMapper.treeToValue(root, ServiceInstance.class);
        instance.setName(serviceName);
        if (!hasIdField) {
            instance.setId(parseInstanceId(key));
        }
        return instance;
    }

    private void sanitizeMetadata(JsonNode root) {
        if (!(root instanceof ObjectNode objectNode)) {
            return;
        }
        JsonNode metadata = objectNode.get("metadata");
        if (!(metadata instanceof ObjectNode metadataNode)) {
            return;
        }
        List<String> invalidKeys = new ArrayList<>();
        metadataNode.properties().forEach(entry -> {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isNull()) {
                invalidKeys.add(entry.getKey());
            }
        });
        invalidKeys.forEach(metadataNode::remove);
    }

    private String parseServiceName(String key) {
        if (!key.startsWith(basePath)) {
            return null;
        }
        String relative = key.substring(basePath.length());
        int idx = relative.indexOf('/');
        if (idx <= 0) {
            return null;
        }
        return relative.substring(0, idx);
    }

    private String parseInstanceId(String key) {
        int idx = key.lastIndexOf('/');
        if (idx < 0 || idx + 1 >= key.length()) {
            return key;
        }
        return key.substring(idx + 1);
    }

    private static final class WatchRegistration {
        private final long id;
        private final String serviceName;
        private final ServiceInstanceListener instanceListener;
        private final ServiceNameListener serviceNameListener;
        // Instance watch: known instances keyed by ServiceInstance#getKey().
        private final ConcurrentMap<String, ServiceInstance> knownInstances;
        // Service-name watch: serviceName -> known instance ids for that service.
        // This also lets watch events detect when the last instance is removed.
        private final ConcurrentMap<String, Set<String>> serviceNameInstances;
        private final boolean serviceNameWatch;
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
        private volatile long currentRetryDelayMs = INITIAL_RETRY_DELAY_MS;
        private volatile Watch.Watcher watcher;
        private volatile boolean closed;

        private WatchRegistration(
                long id,
                String serviceName,
                ServiceInstanceListener instanceListener,
                ServiceNameListener serviceNameListener,
                ConcurrentMap<String, ServiceInstance> knownInstances,
                ConcurrentMap<String, Set<String>> serviceNameInstances,
                boolean serviceNameWatch) {
            this.id = id;
            this.serviceName = serviceName;
            this.instanceListener = instanceListener;
            this.serviceNameListener = serviceNameListener;
            this.knownInstances = knownInstances;
            this.serviceNameInstances = serviceNameInstances;
            this.serviceNameWatch = serviceNameWatch;
        }

        private static WatchRegistration forService(
                long id,
                String serviceName,
                ServiceInstanceListener listener,
                ConcurrentMap<String, ServiceInstance> knownInstances) {
            return new WatchRegistration(id, serviceName, listener, null, knownInstances, null, false);
        }

        private static WatchRegistration forServiceNames(long id, ServiceNameListener listener) {
            return new WatchRegistration(id, null, null, listener, null, new ConcurrentHashMap<>(), true);
        }

        private void close() {
            closed = true;
            Watch.Watcher current = watcher;
            watcher = null;
            if (current != null) {
                current.close();
            }
        }
    }
}
