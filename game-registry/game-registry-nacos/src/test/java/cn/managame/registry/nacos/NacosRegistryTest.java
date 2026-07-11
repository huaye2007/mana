package cn.managame.registry.nacos;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NacosRegistryTest {
    @Test
    void mapsRegistrationAndCleansItUpOnClose() throws Exception {
        NamingService naming = mock(NamingService.class);
        NacosRegistry registry = new NacosRegistry(naming, "games");
        ServiceInstance service = service("node-1", 9001);

        registry.register(service);
        ArgumentCaptor<Instance> registered = ArgumentCaptor.forClass(Instance.class);
        verify(naming).registerInstance(eq("game"), eq("games"), registered.capture());
        assertEquals("node-1", registered.getValue().getMetadata().get(NacosRegistry.ID_METADATA));

        registry.close();
        verify(naming).deregisterInstance(eq("game"), eq("games"), any(Instance.class));
        verify(naming).shutDown();
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

    private static ServiceInstance service(String id, int port) {
        return ServiceInstance.builder().name("game").id(id).address("127.0.0.1").port(port).build();
    }
}
