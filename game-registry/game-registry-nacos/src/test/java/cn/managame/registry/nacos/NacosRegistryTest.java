package cn.managame.registry.nacos;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.exception.RegistryException;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NacosRegistryTest {
    @Test
    void mapsRegistrationAndCleansItUpOnClose() throws Exception {
        NamingService naming = mock(NamingService.class);
        NacosRegistry registry = new NacosRegistry(naming, "games");
        ServiceInstance service = service("node-1", 9001);

        registry.register(service);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Instance>> registered = ArgumentCaptor.forClass(List.class);
        verify(naming).batchRegisterInstance(eq("game"), eq("games"), registered.capture());
        assertEquals("node-1", registered.getValue().getFirst().getMetadata().get(NacosRegistry.ID_METADATA));

        registry.close();
        verify(naming).batchDeregisterInstance(eq("game"), eq("games"), any());
        verify(naming).shutDown();
    }

    @Test
    void publishesCompleteServiceSetAndPreservesRedoStateWhenEndpointChanges() throws Exception {
        NamingService naming = mock(NamingService.class);
        NacosRegistry registry = new NacosRegistry(naming, "games");

        registry.register(service("node-1", 9001));
        registry.register(service("node-2", 9002));
        registry.register(service("node-1", 9011));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Instance>> batches = ArgumentCaptor.forClass(List.class);
        verify(naming, times(3)).batchRegisterInstance(eq("game"), eq("games"), batches.capture());
        List<Instance> latest = batches.getAllValues().getLast();
        assertEquals(List.of("node-1", "node-2"), latest.stream()
                .map(instance -> instance.getMetadata().get(NacosRegistry.ID_METADATA)).sorted().toList());
        assertEquals(9011, latest.stream()
                .filter(instance -> "node-1".equals(instance.getMetadata().get(NacosRegistry.ID_METADATA)))
                .findFirst().orElseThrow().getPort());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Instance>> removed = ArgumentCaptor.forClass(List.class);
        verify(naming).batchDeregisterInstance(eq("game"), eq("games"), removed.capture());
        assertEquals(9001, removed.getValue().getFirst().getPort());
        registry.close();
    }

    @Test
    void watchEmitsInitialSnapshotAndDiffsNacosSnapshots() throws Exception {
        NamingService naming = mock(NamingService.class);
        Instance first = NacosRegistry.toNacos(service("node-1", 9001));
        when(naming.getAllInstances("game", "games")).thenReturn(List.of(first));
        NacosRegistry registry = new NacosRegistry(naming, "games");
        List<ServiceInstanceEvent> events = new ArrayList<>();

        AutoCloseable handle = registry.watchService("game", events::add);
        ArgumentCaptor<EventListener> listener = ArgumentCaptor.forClass(EventListener.class);
        verify(naming).subscribe(eq("game"), eq("games"), listener.capture());
        Instance updated = NacosRegistry.toNacos(service("node-1", 9002));
        listener.getValue().onEvent(new NamingEvent("game", List.of(updated)));
        listener.getValue().onEvent(new NamingEvent("game", List.of()));
        handle.close();

        assertEquals(List.of(DiscoveryEventType.ADDED, DiscoveryEventType.UPDATED, DiscoveryEventType.REMOVED),
                events.stream().map(ServiceInstanceEvent::getType).toList());
        verify(naming).unsubscribe("game", "games", listener.getValue());
    }

    @Test
    void roundTripsMetadataAndIdentity() {
        ServiceInstance original = ServiceInstance.builder().name("game").id("node-1")
                .address("10.0.0.1").port(9001).weight(2.5).healthy(false)
                .metadata(Map.of("zone", "east")).build();
        ServiceInstance restored = NacosRegistry.fromNacos("game", NacosRegistry.toNacos(original));
        assertEquals(original, restored);
    }

    @Test
    void closeWaitsForInFlightRegistrationAndThenCleansItUp() throws Exception {
        NamingService naming = mock(NamingService.class);
        CountDownLatch registerEntered = new CountDownLatch(1);
        CountDownLatch releaseRegister = new CountDownLatch(1);
        CountDownLatch shutdownCalled = new CountDownLatch(1);
        doAnswer(invocation -> {
            registerEntered.countDown();
            assertTrue(releaseRegister.await(2, TimeUnit.SECONDS));
            return null;
        }).when(naming).batchRegisterInstance(eq("game"), eq("games"), any());
        doAnswer(invocation -> {
            shutdownCalled.countDown();
            return null;
        }).when(naming).shutDown();
        NacosRegistry registry = new NacosRegistry(naming, "games");
        AtomicReference<Throwable> registerFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread registering = Thread.startVirtualThread(() -> {
            try {
                registry.register(service("node-1", 9001));
            } catch (Throwable error) {
                registerFailure.set(error);
            }
        });
        assertTrue(registerEntered.await(2, TimeUnit.SECONDS));
        Thread closing = Thread.startVirtualThread(() -> {
            try {
                registry.close();
            } catch (Throwable error) {
                closeFailure.set(error);
            }
        });

        assertFalse(shutdownCalled.await(200, TimeUnit.MILLISECONDS));
        releaseRegister.countDown();
        registering.join();
        closing.join();

        assertNull(registerFailure.get());
        assertNull(closeFailure.get());
        assertTrue(shutdownCalled.await(1, TimeUnit.SECONDS));
        verify(naming).batchDeregisterInstance(eq("game"), eq("games"), any());
    }

    @Test
    void failedBatchRegistrationRollsBackNacosRedoState() throws Exception {
        NamingService naming = mock(NamingService.class);
        whenRegisterBatch(naming, new NacosException(NacosException.SERVER_ERROR, "failed"));
        NacosRegistry registry = new NacosRegistry(naming, "games");

        assertThrows(RegistryException.class, () -> registry.register(service("node-1", 9001)));

        verify(naming).batchDeregisterInstance(eq("game"), eq("games"), any());
        registry.close();
    }

    private static void whenRegisterBatch(NamingService naming, NacosException failure) throws NacosException {
        org.mockito.Mockito.doThrow(failure).when(naming)
                .batchRegisterInstance(eq("game"), eq("games"), any());
    }

    private static ServiceInstance service(String id, int port) {
        return ServiceInstance.builder().name("game").id(id).address("127.0.0.1").port(port).build();
    }
}
