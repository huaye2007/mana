package cn.managame.registry.etcd;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.exception.RegistryException;
import cn.managame.registry.factory.RegistryConfig;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.ClientBuilder;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class EtcdRegistry implements ServiceRegistry {
    private static final Logger log = LoggerFactory.getLogger(EtcdRegistry.class);
    static final String PREFIX_PROPERTY = "prefix";
    static final String TTL_PROPERTY = "leaseTtlSeconds";
    static final String OPERATION_TIMEOUT_PROPERTY = "operationTimeoutMillis";
    static final String USERNAME_PROPERTY = "username";
    static final String PASSWORD_PROPERTY = "password";
    static final String DEFAULT_PREFIX = "/mana/services";
    static final long DEFAULT_TTL_SECONDS = 10;
    static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 5000;
    private static final long RECOVERY_INITIAL_BACKOFF_MILLIS = 100;
    private static final long RECOVERY_MAX_BACKOFF_MILLIS = 5000;
    private static final int FORMAT_VERSION = 1;

    private final Client client;
    private final KV kv;
    private final Lease lease;
    private final Watch watchClient;
    private volatile long leaseId;
    private volatile CloseableClient keepAlive;
    private final long leaseTtlSeconds;
    private final long operationTimeoutMillis;
    private final String prefix;
    private final String owner;
    private final Map<String, ServiceInstance> registrations = new ConcurrentHashMap<>();
    private final Set<EtcdWatch> watches = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object leaseLock = new Object();
    private final AtomicBoolean leaseRecoveryScheduled = new AtomicBoolean();
    private final AtomicInteger leaseRecoveryAttempts = new AtomicInteger();
    private final ScheduledExecutorService recoveryExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("etcd-registry-recovery-", 0).factory());

    EtcdRegistry(RegistryConfig config) {
        Client created = createClient(config);
        long ttl = parseTtl(config.getProperties().get(TTL_PROPERTY));
        long timeoutMillis = parsePositiveLong(config.getProperties().get(OPERATION_TIMEOUT_PROPERTY),
                DEFAULT_OPERATION_TIMEOUT_MILLIS, OPERATION_TIMEOUT_PROPERTY);
        try {
            Lease createdLease = created.getLeaseClient();
            client = created;
            kv = created.getKVClient();
            lease = createdLease;
            watchClient = created.getWatchClient();
            leaseTtlSeconds = ttl;
            operationTimeoutMillis = timeoutMillis;
            prefix = normalizePrefix(config.getProperties().getOrDefault(PREFIX_PROPERTY, DEFAULT_PREFIX));
            owner = UUID.randomUUID().toString();
            long createdLeaseId = await(createdLease.grant(ttl), "grant etcd lease").getID();
            leaseId = createdLeaseId;
            keepAlive = createdLease.keepAlive(createdLeaseId, new KeepAliveObserver(createdLeaseId));
        } catch (RuntimeException e) {
            recoveryExecutor.shutdownNow();
            created.close();
            throw e;
        }
    }

    EtcdRegistry(Client client, long leaseId, CloseableClient keepAlive, String prefix, String owner) {
        this.client = Objects.requireNonNull(client, "client");
        this.kv = client.getKVClient();
        this.lease = client.getLeaseClient();
        this.watchClient = client.getWatchClient();
        this.leaseTtlSeconds = DEFAULT_TTL_SECONDS;
        this.operationTimeoutMillis = DEFAULT_OPERATION_TIMEOUT_MILLIS;
        this.leaseId = leaseId;
        this.keepAlive = Objects.requireNonNull(keepAlive, "keepAlive");
        this.prefix = normalizePrefix(prefix);
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public void register(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        synchronized (leaseLock) {
            requireOpen();
            putOnLease(instance, leaseId);
            registrations.put(registrationKey(instance), instance);
        }
    }

    @Override
    public void deregister(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        synchronized (leaseLock) {
            requireOpen();
            String registrationKey = registrationKey(instance);
            ServiceInstance owned = registrations.get(registrationKey);
            if (owned == null) return;
            deleteOwned(owned);
            registrations.remove(registrationKey, owned);
        }
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName) {
        requireOpen();
        requireServiceName(serviceName);
        GetResponse response = await(kv.get(servicePrefix(serviceName), prefixGet()),
                "get instances for " + serviceName);
        return decodeInstances(response.getKvs());
    }

    @Override
    public AutoCloseable watchService(String serviceName, ServiceInstanceListener listener) {
        requireOpen();
        requireServiceName(serviceName);
        Objects.requireNonNull(listener, "listener");
        GetResponse response = await(kv.get(servicePrefix(serviceName), prefixGet()),
                "get initial instances for " + serviceName);
        EtcdWatch subscription = new EtcdWatch(serviceName, listener);
        watches.add(subscription);
        try {
            subscription.start(response);
            return subscription;
        } catch (RuntimeException e) {
            subscription.close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        recoveryExecutor.shutdownNow();
        RuntimeException failure = null;
        for (EtcdWatch watch : List.copyOf(watches)) {
            try {
                watch.close();
            } catch (RuntimeException e) {
                failure = append(failure, e);
            }
        }
        synchronized (leaseLock) {
            try {
                keepAlive.close();
            } catch (RuntimeException e) {
                failure = append(failure, e);
            }
            try {
                await(lease.revoke(leaseId), "revoke etcd lease");
            } catch (RuntimeException e) {
                failure = append(failure, e);
            }
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            failure = append(failure, e);
        }
        registrations.clear();
        if (failure != null) throw failure;
    }

    private void deleteOwned(ServiceInstance instance) {
        ByteSequence key = key(instance);
        ByteSequence value = encode(owner, instance);
        Cmp ownedValue = new Cmp(key, Cmp.Op.EQUAL, CmpTarget.value(value));
        Op delete = Op.delete(key, DeleteOption.DEFAULT);
        await(kv.txn().If(ownedValue).Then(delete).commit(), "deregister " + instance);
    }

    private void putOnLease(ServiceInstance instance, long targetLeaseId) {
        PutOption option = PutOption.builder().withLeaseId(targetLeaseId).build();
        await(kv.put(key(instance), encode(owner, instance), option), "register " + instance);
    }

    private ByteSequence key(ServiceInstance instance) {
        return bytes(servicePath(instance.getName()) + '/' + pathSegment(instance.getKey()));
    }

    private ByteSequence servicePrefix(String serviceName) {
        return bytes(servicePath(serviceName) + '/');
    }

    private String servicePath(String serviceName) {
        return prefix + '/' + pathSegment(serviceName);
    }

    private static GetOption prefixGet() {
        return GetOption.builder().isPrefix(true).build();
    }

    private static List<ServiceInstance> decodeInstances(List<KeyValue> values) {
        return values.stream().map(value -> decode(value.getValue()).instance())
                .sorted(Comparator.comparing(ServiceInstance::getKey)).toList();
    }

    static ByteSequence encode(String owner, ServiceInstance instance) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(FORMAT_VERSION);
                output.writeUTF(owner);
                output.writeUTF(instance.getName());
                output.writeBoolean(instance.getId() != null);
                if (instance.getId() != null) output.writeUTF(instance.getId());
                output.writeUTF(instance.getAddress());
                output.writeInt(instance.getPort());
                output.writeDouble(instance.getWeight());
                output.writeBoolean(instance.isHealthy());
                List<Map.Entry<String, String>> metadata = instance.getMetadata().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList();
                output.writeInt(metadata.size());
                for (Map.Entry<String, String> entry : metadata) {
                    output.writeUTF(entry.getKey());
                    output.writeUTF(entry.getValue());
                }
            }
            return ByteSequence.from(buffer.toByteArray());
        } catch (IOException e) {
            throw new RegistryException("failed to encode service instance", e);
        }
    }

    static Envelope decode(ByteSequence value) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value.getBytes()))) {
            int version = input.readInt();
            if (version != FORMAT_VERSION) throw new RegistryException("unsupported etcd registry value version: " + version);
            String owner = input.readUTF();
            ServiceInstance.Builder builder = ServiceInstance.builder().name(input.readUTF());
            if (input.readBoolean()) builder.id(input.readUTF());
            builder.address(input.readUTF()).port(input.readInt()).weight(input.readDouble()).healthy(input.readBoolean());
            int metadataSize = input.readInt();
            if (metadataSize < 0 || metadataSize > 10_000) throw new RegistryException("invalid metadata size: " + metadataSize);
            Map<String, String> metadata = new HashMap<>();
            for (int i = 0; i < metadataSize; i++) metadata.put(input.readUTF(), input.readUTF());
            return new Envelope(owner, builder.metadata(metadata).build());
        } catch (IOException | IllegalArgumentException e) {
            throw new RegistryException("failed to decode etcd service instance", e);
        }
    }

    private static Client createClient(RegistryConfig config) {
        String[] endpoints = java.util.Arrays.stream(config.getEndpoints().split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
        if (endpoints.length == 0) throw new IllegalArgumentException("etcd endpoints must not be blank");
        ClientBuilder builder = Client.builder().endpoints(endpoints);
        String username = config.getProperties().get(USERNAME_PROPERTY);
        String password = config.getProperties().get(PASSWORD_PROPERTY);
        if (username != null && !username.isBlank()) builder.user(bytes(username));
        if (password != null) builder.password(bytes(password));
        return builder.build();
    }

    private static long parseTtl(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TTL_SECONDS;
        try {
            long ttl = Long.parseLong(value.trim());
            if (ttl <= 0) throw new IllegalArgumentException("leaseTtlSeconds must be positive");
            return ttl;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("leaseTtlSeconds must be a positive integer", e);
        }
    }

    private static long parsePositiveLong(String value, long defaultValue, String property) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) throw new IllegalArgumentException(property + " must be positive");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be a positive integer", e);
        }
    }

    private static String normalizePrefix(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("etcd prefix must not be blank");
        String result = value.trim();
        if (!result.startsWith("/")) result = '/' + result;
        while (result.length() > 1 && result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String pathSegment(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    private static String registrationKey(ServiceInstance instance) {
        return instance.getName() + '\0' + instance.getKey();
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("registry is closed");
    }

    private static void requireServiceName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("service name must not be blank");
    }

    private <T> T await(CompletableFuture<T> future, String action) {
        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RegistryException("timed out after " + operationTimeoutMillis + "ms while attempting to " + action, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegistryException("interrupted while attempting to " + action, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RegistryException("failed to " + action, cause);
        }
    }

    private void scheduleLeaseRecovery(long observedLeaseId, Throwable cause) {
        if (closed.get() || leaseId != observedLeaseId) return;
        if (!leaseRecoveryScheduled.compareAndSet(false, true)) return;
        int attempt = leaseRecoveryAttempts.getAndIncrement();
        long delay = recoveryDelay(attempt);
        log.warn("etcd lease keepalive lost; scheduling recovery in {}ms, leaseId={}",
                delay, observedLeaseId, cause);
        try {
            recoveryExecutor.schedule(() -> recoverLease(observedLeaseId), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            leaseRecoveryScheduled.set(false);
        }
    }

    void keepAliveFailed(long observedLeaseId, Throwable cause) {
        scheduleLeaseRecovery(observedLeaseId, cause);
    }

    private void recoverLease(long observedLeaseId) {
        boolean recovered = false;
        try {
            synchronized (leaseLock) {
                if (closed.get() || leaseId != observedLeaseId) return;
                long newLeaseId = await(lease.grant(leaseTtlSeconds), "re-grant etcd lease").getID();
                CloseableClient newKeepAlive = null;
                try {
                    for (ServiceInstance instance : List.copyOf(registrations.values())) {
                        putOnLease(instance, newLeaseId);
                    }
                    newKeepAlive = lease.keepAlive(newLeaseId, new KeepAliveObserver(newLeaseId));
                } catch (RuntimeException recoveryFailure) {
                    if (newKeepAlive != null) {
                        try { newKeepAlive.close(); } catch (RuntimeException closeFailure) {
                            recoveryFailure.addSuppressed(closeFailure);
                        }
                    }
                    try { await(lease.revoke(newLeaseId), "revoke failed recovery lease"); }
                    catch (RuntimeException revokeFailure) { recoveryFailure.addSuppressed(revokeFailure); }
                    throw recoveryFailure;
                }

                CloseableClient oldKeepAlive = keepAlive;
                long oldLeaseId = leaseId;
                leaseId = newLeaseId;
                keepAlive = newKeepAlive;
                recovered = true;
                try { oldKeepAlive.close(); }
                catch (RuntimeException e) { log.debug("failed to close obsolete etcd keepalive", e); }
                try { await(lease.revoke(oldLeaseId), "revoke obsolete etcd lease"); }
                catch (RuntimeException e) { log.debug("failed to revoke obsolete etcd lease {}", oldLeaseId, e); }
                log.info("etcd lease recovered and {} registrations restored, oldLeaseId={}, newLeaseId={}",
                        registrations.size(), oldLeaseId, newLeaseId);
            }
        } catch (RuntimeException failure) {
            log.warn("etcd lease recovery attempt failed, leaseId={}", observedLeaseId, failure);
        } finally {
            leaseRecoveryScheduled.set(false);
            if (recovered) {
                leaseRecoveryAttempts.set(0);
            } else if (!closed.get() && leaseId == observedLeaseId) {
                scheduleLeaseRecovery(observedLeaseId,
                        new RegistryException("previous etcd lease recovery attempt failed"));
            }
        }
    }

    private static long recoveryDelay(int attempt) {
        int shift = Math.min(attempt, 10);
        return Math.min(RECOVERY_MAX_BACKOFF_MILLIS, RECOVERY_INITIAL_BACKOFF_MILLIS << shift);
    }

    private static RuntimeException append(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    record Envelope(String owner, ServiceInstance instance) {
    }

    private final class EtcdWatch implements AutoCloseable {
        private final String serviceName;
        private final ServiceInstanceListener listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
        private final AtomicInteger reconnectAttempts = new AtomicInteger();
        private final List<ServiceInstanceEvent> pending = new ArrayList<>();
        private final Map<String, ServiceInstance> known = new HashMap<>();
        private boolean ready;
        private long generation;
        private Watch.Watcher watcher;

        private EtcdWatch(String serviceName, ServiceInstanceListener listener) {
            this.serviceName = serviceName;
            this.listener = listener;
        }

        private void start(GetResponse response) {
            final long token;
            synchronized (this) {
                if (!active.get()) return;
                token = ++generation;
                ready = false;
                pending.clear();
            }
            WatchOption option = WatchOption.builder()
                    .isPrefix(true)
                    .withPrevKV(true)
                    .withRevision(response.getHeader().getRevision() + 1)
                    .build();
            Watch.Listener delegate = new Watch.Listener() {
                @Override public void onNext(WatchResponse value) { acceptResponse(token, value); }
                @Override public void onError(Throwable throwable) { watchFailed(token, throwable); }
                @Override public void onCompleted() {
                    watchFailed(token, new RegistryException("etcd watch completed unexpectedly"));
                }
            };
            Watch.Watcher installed = watchClient.watch(servicePrefix(serviceName), option, delegate);
            Watch.Watcher previous;
            synchronized (this) {
                if (!active.get() || token != generation) {
                    installed.close();
                    return;
                }
                previous = watcher;
                watcher = installed;
                reconcile(decodeInstances(response.getKvs()));
            }
            if (previous != null && previous != installed) previous.close();
        }

        private void acceptResponse(long token, WatchResponse response) {
            try {
                for (WatchEvent event : response.getEvents()) {
                    KeyValue value = event.getEventType() == WatchEvent.EventType.DELETE
                            ? event.getPrevKV() : event.getKeyValue();
                    if (value == null || value.getValue() == null || value.getValue().isEmpty()) continue;
                    DiscoveryEventType type = switch (event.getEventType()) {
                        case DELETE -> DiscoveryEventType.REMOVED;
                        case PUT -> value.getCreateRevision() == value.getModRevision()
                                ? DiscoveryEventType.ADDED : DiscoveryEventType.UPDATED;
                        default -> null;
                    };
                    if (type != null) accept(token,
                            new ServiceInstanceEvent(type, decode(value.getValue()).instance()));
                }
            } catch (RuntimeException error) {
                watchFailed(token, error);
            }
        }

        private synchronized void accept(long token, ServiceInstanceEvent event) {
            if (!active.get() || token != generation) return;
            if (!ready) pending.add(event);
            else applyEvent(event);
        }

        private void watchFailed(long token, Throwable throwable) {
            synchronized (this) {
                if (!active.get() || token != generation) return;
                ready = false;
            }
            scheduleReconnect(throwable);
        }

        private void scheduleReconnect(Throwable cause) {
            if (!active.get() || closed.get() || !reconnectScheduled.compareAndSet(false, true)) return;
            int attempt = reconnectAttempts.getAndIncrement();
            long delay = recoveryDelay(attempt);
            log.warn("etcd watch lost; scheduling snapshot reconciliation in {}ms, service={}",
                    delay, serviceName, cause);
            try {
                recoveryExecutor.schedule(() -> {
                    reconnectScheduled.set(false);
                    if (!active.get() || closed.get()) return;
                    try {
                        GetResponse response = await(kv.get(servicePrefix(serviceName), prefixGet()),
                                "reconcile instances for " + serviceName);
                        start(response);
                        reconnectAttempts.set(0);
                        log.info("etcd watch recovered, service={}", serviceName);
                    } catch (RuntimeException error) {
                        scheduleReconnect(error);
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                reconnectScheduled.set(false);
            }
        }

        private void reconcile(List<ServiceInstance> latestInstances) {
            Map<String, ServiceInstance> latest = new HashMap<>();
            latestInstances.forEach(instance -> latest.put(instance.getKey(), instance));
            for (ServiceInstance previous : List.copyOf(known.values())) {
                if (!latest.containsKey(previous.getKey())) {
                    emit(new ServiceInstanceEvent(DiscoveryEventType.REMOVED, previous));
                }
            }
            for (ServiceInstance current : latest.values()) {
                ServiceInstance previous = known.get(current.getKey());
                if (previous == null) {
                    emit(new ServiceInstanceEvent(DiscoveryEventType.ADDED, current));
                } else if (!previous.equals(current)) {
                    emit(new ServiceInstanceEvent(DiscoveryEventType.UPDATED, current));
                }
            }
            known.clear();
            known.putAll(latest);
            ready = true;
            List<ServiceInstanceEvent> queued = List.copyOf(pending);
            pending.clear();
            queued.forEach(this::applyEvent);
        }

        private void applyEvent(ServiceInstanceEvent event) {
            if (event.getType() == DiscoveryEventType.REMOVED) {
                known.remove(event.getInstance().getKey());
            } else {
                known.put(event.getInstance().getKey(), event.getInstance());
            }
            emit(event);
        }

        private void emit(ServiceInstanceEvent event) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException error) {
                log.warn("service instance listener failed, service={}, event={}", serviceName, event.getType(), error);
            }
        }

        @Override
        public synchronized void close() {
            if (!active.compareAndSet(true, false)) return;
            generation++;
            ready = false;
            pending.clear();
            if (watcher != null) watcher.close();
            watches.remove(this);
        }
    }

    private final class KeepAliveObserver implements StreamObserver<io.etcd.jetcd.lease.LeaseKeepAliveResponse> {
        private final long observedLeaseId;

        private KeepAliveObserver(long observedLeaseId) {
            this.observedLeaseId = observedLeaseId;
        }

        @Override public void onNext(io.etcd.jetcd.lease.LeaseKeepAliveResponse value) {
            if (value.getTTL() <= 0) {
                keepAliveFailed(observedLeaseId,
                        new RegistryException("etcd lease keepalive returned a non-positive TTL"));
            }
        }
        @Override public void onError(Throwable throwable) {
            keepAliveFailed(observedLeaseId, throwable);
        }
        @Override public void onCompleted() {
            keepAliveFailed(observedLeaseId,
                    new RegistryException("etcd lease keepalive completed unexpectedly"));
        }
    }
}
