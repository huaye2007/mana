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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EtcdRegistry implements ServiceRegistry {
    static final String PREFIX_PROPERTY = "prefix";
    static final String TTL_PROPERTY = "leaseTtlSeconds";
    static final String USERNAME_PROPERTY = "username";
    static final String PASSWORD_PROPERTY = "password";
    static final String DEFAULT_PREFIX = "/mana/services";
    static final long DEFAULT_TTL_SECONDS = 10;
    private static final int FORMAT_VERSION = 1;

    private final Client client;
    private final KV kv;
    private final Lease lease;
    private final Watch watchClient;
    private final long leaseId;
    private final CloseableClient keepAlive;
    private final String prefix;
    private final String owner;
    private final Map<String, ServiceInstance> registrations = new ConcurrentHashMap<>();
    private final Set<EtcdWatch> watches = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    EtcdRegistry(RegistryConfig config) {
        Client created = createClient(config);
        try {
            Lease createdLease = created.getLeaseClient();
            long ttl = parseTtl(config.getProperties().get(TTL_PROPERTY));
            long createdLeaseId = await(createdLease.grant(ttl), "grant etcd lease").getID();
            CloseableClient keepAliveHandle = createdLease.keepAlive(createdLeaseId, new KeepAliveObserver());
            client = created;
            kv = created.getKVClient();
            lease = createdLease;
            watchClient = created.getWatchClient();
            leaseId = createdLeaseId;
            keepAlive = keepAliveHandle;
            prefix = normalizePrefix(config.getProperties().getOrDefault(PREFIX_PROPERTY, DEFAULT_PREFIX));
            owner = UUID.randomUUID().toString();
        } catch (RuntimeException e) {
            created.close();
            throw e;
        }
    }

    EtcdRegistry(Client client, long leaseId, CloseableClient keepAlive, String prefix, String owner) {
        this.client = Objects.requireNonNull(client, "client");
        this.kv = client.getKVClient();
        this.lease = client.getLeaseClient();
        this.watchClient = client.getWatchClient();
        this.leaseId = leaseId;
        this.keepAlive = Objects.requireNonNull(keepAlive, "keepAlive");
        this.prefix = normalizePrefix(prefix);
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public void register(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        ByteSequence key = key(instance);
        ByteSequence value = encode(owner, instance);
        PutOption option = PutOption.builder().withLeaseId(leaseId).build();
        await(kv.put(key, value, option), "register " + instance);
        registrations.put(registrationKey(instance), instance);
    }

    @Override
    public void deregister(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        String registrationKey = registrationKey(instance);
        ServiceInstance owned = registrations.get(registrationKey);
        if (owned == null) return;
        deleteOwned(owned);
        registrations.remove(registrationKey, owned);
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
        EtcdWatch subscription = new EtcdWatch(listener);
        WatchOption option = WatchOption.builder()
                .isPrefix(true)
                .withPrevKV(true)
                .withRevision(response.getHeader().getRevision() + 1)
                .build();
        subscription.watcher = watchClient.watch(servicePrefix(serviceName), option, subscription);
        watches.add(subscription);
        try {
            subscription.initialize(decodeInstances(response.getKvs()));
            return subscription;
        } catch (RuntimeException e) {
            subscription.close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        for (EtcdWatch watch : List.copyOf(watches)) {
            try {
                watch.close();
            } catch (RuntimeException e) {
                failure = append(failure, e);
            }
        }
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

    private static <T> T await(CompletableFuture<T> future, String action) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RegistryException("failed to " + action, cause);
        }
    }

    private static RuntimeException append(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    record Envelope(String owner, ServiceInstance instance) {
    }

    private final class EtcdWatch implements Watch.Listener, AutoCloseable {
        private final ServiceInstanceListener listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final List<ServiceInstanceEvent> pending = new ArrayList<>();
        private boolean initialized;
        private Watch.Watcher watcher;

        private EtcdWatch(ServiceInstanceListener listener) {
            this.listener = listener;
        }

        private synchronized void initialize(List<ServiceInstance> initial) {
            if (!active.get()) return;
            initial.forEach(instance -> listener.onEvent(new ServiceInstanceEvent(DiscoveryEventType.ADDED, instance)));
            pending.forEach(listener::onEvent);
            pending.clear();
            initialized = true;
        }

        @Override
        public void onNext(WatchResponse response) {
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
                if (type != null) accept(new ServiceInstanceEvent(type, decode(value.getValue()).instance()));
            }
        }

        private synchronized void accept(ServiceInstanceEvent event) {
            if (!active.get()) return;
            if (!initialized) pending.add(event);
            else listener.onEvent(event);
        }

        @Override
        public void onError(Throwable throwable) {
            active.set(false);
            watches.remove(this);
        }

        @Override
        public void onCompleted() {
            active.set(false);
            watches.remove(this);
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) return;
            if (watcher != null) watcher.close();
            watches.remove(this);
        }
    }

    private static final class KeepAliveObserver implements StreamObserver<io.etcd.jetcd.lease.LeaseKeepAliveResponse> {
        @Override public void onNext(io.etcd.jetcd.lease.LeaseKeepAliveResponse value) { }
        @Override public void onError(Throwable throwable) { }
        @Override public void onCompleted() { }
    }
}
