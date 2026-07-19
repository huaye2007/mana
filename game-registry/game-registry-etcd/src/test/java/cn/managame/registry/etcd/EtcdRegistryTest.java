package cn.managame.registry.etcd;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Txn;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.lease.LeaseRevokeResponse;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;

class EtcdRegistryTest {
    private Client client;
    private KV kv;
    private Lease lease;
    private Watch watchClient;
    private CloseableClient keepAlive;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        kv = mock(KV.class);
        lease = mock(Lease.class);
        watchClient = mock(Watch.class);
        keepAlive = mock(CloseableClient.class);
        when(client.getKVClient()).thenReturn(kv);
        when(client.getLeaseClient()).thenReturn(lease);
        when(client.getWatchClient()).thenReturn(watchClient);
    }

    @Test
    void registersOnLeaseAndRevokesLeaseOnClose() {
        when(kv.put(any(), any(), any(PutOption.class))).thenReturn(CompletableFuture.completedFuture(mock()));
        when(lease.revoke(42L)).thenReturn(CompletableFuture.completedFuture(mock(LeaseRevokeResponse.class)));
        EtcdRegistry registry = registry();

        registry.register(instance("node-1", 9001));
        verify(kv).put(any(), any(), any(PutOption.class));

        registry.close();
        verify(keepAlive).close();
        verify(lease).revoke(42L);
        verify(client).close();
    }

    @Test
    void deregistrationUsesCompareAndDeleteTransaction() {
        when(kv.put(any(), any(), any(PutOption.class))).thenReturn(CompletableFuture.completedFuture(mock()));
        Txn txn = mock(Txn.class);
        when(kv.txn()).thenReturn(txn);
        when(txn.If(any())).thenReturn(txn);
        when(txn.Then(any())).thenReturn(txn);
        when(txn.commit()).thenReturn(CompletableFuture.completedFuture(mock(TxnResponse.class)));
        EtcdRegistry registry = registry();
        ServiceInstance instance = instance("node-1", 9001);

        registry.register(instance);
        registry.deregister(instance);

        verify(txn).If(any());
        verify(txn).Then(any());
        verify(txn).commit();
    }

    @Test
    void watchEmitsInitialSnapshotThenIncrementalEvents() throws Exception {
        ServiceInstance first = instance("node-1", 9001);
        KeyValue initialValue = keyValue(first, 1, 1);
        GetResponse response = mock(GetResponse.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(response.getKvs()).thenReturn(List.of(initialValue));
        when(response.getHeader().getRevision()).thenReturn(10L);
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));
        Watch.Watcher watcher = mock(Watch.Watcher.class);
        ArgumentCaptor<Watch.Listener> listener = ArgumentCaptor.forClass(Watch.Listener.class);
        when(watchClient.watch(any(), any(WatchOption.class), listener.capture())).thenReturn(watcher);
        EtcdRegistry registry = registry();
        List<ServiceInstanceEvent> events = new ArrayList<>();

        AutoCloseable handle = registry.watchService("game", events::add);
        ServiceInstance updated = instance("node-1", 9002);
        WatchEvent put = mock(WatchEvent.class);
        KeyValue updatedValue = keyValue(updated, 1, 2);
        when(put.getEventType()).thenReturn(WatchEvent.EventType.PUT);
        when(put.getKeyValue()).thenReturn(updatedValue);
        WatchResponse update = mock(WatchResponse.class);
        when(update.getEvents()).thenReturn(List.of(put));
        listener.getValue().onNext(update);
        handle.close();

        assertEquals(List.of(DiscoveryEventType.ADDED, DiscoveryEventType.UPDATED),
                events.stream().map(ServiceInstanceEvent::getType).toList());
        assertEquals(9002, events.getLast().getInstance().getPort());
        verify(watcher).close();
    }

    @Test
    void valueCodecRoundTripsIdentityAndMetadata() {
        ServiceInstance original = ServiceInstance.builder().name("game").id("node-1")
                .address("10.0.0.1").port(9001).weight(2.5).healthy(false)
                .metadata(Map.of("zone", "east")).build();
        EtcdRegistry.Envelope decoded = EtcdRegistry.decode(EtcdRegistry.encode("owner-1", original));
        assertEquals("owner-1", decoded.owner());
        assertEquals(original, decoded.instance());
    }

    @Test
    void watchReconnectsAndReconcilesMissedChanges() throws Exception {
        ServiceInstance first = instance("node-1", 9001);
        ServiceInstance second = instance("node-2", 9002);
        GetResponse initial = response(List.of(keyValue(first, 1, 1)), 10L);
        GetResponse recovered = response(List.of(keyValue(second, 2, 2)), 20L);
        when(kv.get(any(), any(GetOption.class))).thenReturn(
                CompletableFuture.completedFuture(initial), CompletableFuture.completedFuture(recovered));
        Watch.Watcher firstWatcher = mock(Watch.Watcher.class);
        Watch.Watcher secondWatcher = mock(Watch.Watcher.class);
        ArgumentCaptor<Watch.Listener> listeners = ArgumentCaptor.forClass(Watch.Listener.class);
        when(watchClient.watch(any(), any(WatchOption.class), listeners.capture()))
                .thenReturn(firstWatcher, secondWatcher);
        when(lease.revoke(anyLong())).thenReturn(
                CompletableFuture.completedFuture(mock(LeaseRevokeResponse.class)));
        EtcdRegistry registry = registry();
        List<ServiceInstanceEvent> events = new CopyOnWriteArrayList<>();

        AutoCloseable handle = registry.watchService("game", events::add);
        listeners.getAllValues().getFirst().onError(new IllegalStateException("lost"));

        verify(watchClient, timeout(2000).times(2)).watch(any(), any(WatchOption.class), any(Watch.Listener.class));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (events.size() < 3 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(List.of(DiscoveryEventType.ADDED, DiscoveryEventType.REMOVED, DiscoveryEventType.ADDED),
                events.stream().map(ServiceInstanceEvent::getType).toList());
        assertEquals("node-2", events.getLast().getInstance().getKey());

        handle.close();
        registry.close();
    }

    @Test
    void keepAliveFailureRegrantsLeaseAndRestoresRegistrations() {
        when(kv.put(any(), any(), any(PutOption.class))).thenReturn(CompletableFuture.completedFuture(mock()));
        LeaseGrantResponse grant = mock(LeaseGrantResponse.class);
        when(grant.getID()).thenReturn(84L);
        when(lease.grant(anyLong())).thenReturn(CompletableFuture.completedFuture(grant));
        CloseableClient recoveredKeepAlive = mock(CloseableClient.class);
        when(lease.keepAlive(anyLong(), any())).thenReturn(recoveredKeepAlive);
        when(lease.revoke(anyLong())).thenReturn(
                CompletableFuture.completedFuture(mock(LeaseRevokeResponse.class)));
        EtcdRegistry registry = registry();
        registry.register(instance("node-1", 9001));

        registry.keepAliveFailed(42L, new IllegalStateException("lost"));

        verify(lease, timeout(2000)).grant(EtcdRegistry.DEFAULT_TTL_SECONDS);
        verify(kv, timeout(2000).times(2)).put(any(), any(), any(PutOption.class));
        verify(lease, timeout(2000)).keepAlive(anyLong(), any());
        verify(keepAlive, timeout(2000)).close();
        registry.close();
        verify(recoveredKeepAlive).close();
    }

    @Test
    void immediateFailureOfRecoveredKeepAliveTriggersAnotherRecovery() {
        when(kv.put(any(), any(), any(PutOption.class))).thenReturn(CompletableFuture.completedFuture(mock()));
        LeaseGrantResponse firstGrant = mock(LeaseGrantResponse.class);
        LeaseGrantResponse secondGrant = mock(LeaseGrantResponse.class);
        when(firstGrant.getID()).thenReturn(84L);
        when(secondGrant.getID()).thenReturn(126L);
        when(lease.grant(anyLong())).thenReturn(
                CompletableFuture.completedFuture(firstGrant), CompletableFuture.completedFuture(secondGrant));
        CloseableClient failedKeepAlive = mock(CloseableClient.class);
        CloseableClient stableKeepAlive = mock(CloseableClient.class);
        when(lease.keepAlive(anyLong(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            io.grpc.stub.StreamObserver<io.etcd.jetcd.lease.LeaseKeepAliveResponse> observer = invocation.getArgument(1);
            observer.onError(new IllegalStateException("failed immediately"));
            return failedKeepAlive;
        }).thenReturn(stableKeepAlive);
        when(lease.revoke(anyLong())).thenReturn(
                CompletableFuture.completedFuture(mock(LeaseRevokeResponse.class)));
        EtcdRegistry registry = registry();
        registry.register(instance("node-1", 9001));

        registry.keepAliveFailed(42L, new IllegalStateException("lost"));

        verify(lease, timeout(3000).times(2)).grant(EtcdRegistry.DEFAULT_TTL_SECONDS);
        verify(lease, timeout(3000).times(2)).keepAlive(anyLong(), any());
        registry.close();
        verify(stableKeepAlive).close();
    }

    @Test
    void blockedWatchReconciliationDoesNotBlockLeaseRecovery() throws Exception {
        ServiceInstance recoveredInstance = instance("node-1", 9001);
        GetResponse initial = response(List.of(), 10L);
        GetResponse recovered = response(List.of(keyValue(recoveredInstance, 1, 1)), 20L);
        when(kv.get(any(), any(GetOption.class))).thenReturn(
                CompletableFuture.completedFuture(initial), CompletableFuture.completedFuture(recovered));
        ArgumentCaptor<Watch.Listener> listeners = ArgumentCaptor.forClass(Watch.Listener.class);
        when(watchClient.watch(any(), any(WatchOption.class), listeners.capture()))
                .thenReturn(mock(Watch.Watcher.class), mock(Watch.Watcher.class));
        LeaseGrantResponse grant = mock(LeaseGrantResponse.class);
        when(grant.getID()).thenReturn(84L);
        when(lease.grant(anyLong())).thenReturn(CompletableFuture.completedFuture(grant));
        when(lease.keepAlive(anyLong(), any())).thenReturn(mock(CloseableClient.class));
        when(lease.revoke(anyLong())).thenReturn(
                CompletableFuture.completedFuture(mock(LeaseRevokeResponse.class)));
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        EtcdRegistry registry = registry();
        AutoCloseable handle = registry.watchService("game", event -> {
            listenerEntered.countDown();
            try {
                releaseListener.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            listeners.getAllValues().getFirst().onError(new IllegalStateException("watch lost"));
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));

            registry.keepAliveFailed(42L, new IllegalStateException("lease lost"));

            verify(lease, timeout(2000)).grant(EtcdRegistry.DEFAULT_TTL_SECONDS);
        } finally {
            releaseListener.countDown();
            handle.close();
            registry.close();
        }
    }

    private EtcdRegistry registry() {
        return new EtcdRegistry(client, 42L, keepAlive, "/test/services", "owner-1");
    }

    private static GetResponse response(List<KeyValue> values, long revision) {
        GetResponse response = mock(GetResponse.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(response.getKvs()).thenReturn(values);
        when(response.getHeader().getRevision()).thenReturn(revision);
        return response;
    }

    private static KeyValue keyValue(ServiceInstance instance, long createRevision, long modRevision) {
        KeyValue value = mock(KeyValue.class);
        when(value.getValue()).thenReturn(EtcdRegistry.encode("owner-x", instance));
        when(value.getCreateRevision()).thenReturn(createRevision);
        when(value.getModRevision()).thenReturn(modRevision);
        return value;
    }

    private static ServiceInstance instance(String id, int port) {
        return ServiceInstance.builder().name("game").id(id).address("127.0.0.1").port(port).build();
    }
}
