package cn.managame.registry.memory;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceNameEvent;
import cn.managame.registry.exception.RegistryOperationException;
import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryFactory;
import cn.managame.registry.factory.RegistryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRegistryTest {

    @AfterEach
    void cleanUp() {
        MemoryRegistry.resetAllNamespaces();
    }

    private static ServiceInstance instance(String name, String id, int port) {
        return ServiceInstance.builder()
                .name(name)
                .id(id)
                .address("127.0.0.1")
                .port(port)
                .build();
    }

    @Test
    void registerAndDiscover() {
        try (MemoryRegistry registry = new MemoryRegistry("register-and-discover")) {
            registry.start();
            registry.register(instance("logic-server", "logic-1", 9001));
            registry.register(instance("logic-server", "logic-2", 9002));
            registry.register(instance("gate-server", "gate-1", 8001));

            assertEquals(List.of("gate-server", "logic-server"), List.copyOf(registry.getServiceNames()));
            List<ServiceInstance> instances = List.copyOf(registry.getInstances("logic-server"));
            assertEquals(2, instances.size());
            assertEquals("logic-1", instances.get(0).getId());
            assertEquals("logic-2", instances.get(1).getId());
            assertTrue(registry.getInstances("unknown-server").isEmpty());
        }
    }

    @Test
    void unregisterRemovesInstanceAndEmptyService() {
        try (MemoryRegistry registry = new MemoryRegistry("unregister")) {
            registry.start();
            ServiceInstance logic = instance("logic-server", "logic-1", 9001);
            registry.register(logic);
            registry.unregister(logic);

            assertTrue(registry.getInstances("logic-server").isEmpty());
            assertTrue(registry.getServiceNames().isEmpty());
        }
    }

    @Test
    void bundlesInSameNamespaceShareStore() {
        RegistryConfig config = RegistryConfig.builder()
                .type(RegistryType.MEMORY)
                .endpoints("shared-namespace")
                .build();
        try (RegistryBundle serverBundle = RegistryFactory.create(config);
             RegistryBundle clientBundle = RegistryFactory.create(config)) {
            serverBundle.start();
            clientBundle.start();

            serverBundle.getRegistry().register(instance("logic-server", "logic-1", 9001));

            List<ServiceInstance> discovered =
                    List.copyOf(clientBundle.getDiscovery().getInstances("logic-server"));
            assertEquals(1, discovered.size());
            assertEquals("logic-1", discovered.get(0).getId());
        }
    }

    @Test
    void namespacesAreIsolated() {
        try (MemoryRegistry left = new MemoryRegistry("namespace-left");
             MemoryRegistry right = new MemoryRegistry("namespace-right")) {
            left.start();
            right.start();
            left.register(instance("logic-server", "logic-1", 9001));

            assertTrue(right.getInstances("logic-server").isEmpty());
            assertTrue(right.getServiceNames().isEmpty());
        }
    }

    @Test
    void watchServiceEmitsInitialSnapshotAndChanges() throws Exception {
        try (MemoryRegistry registry = new MemoryRegistry("watch-service")) {
            registry.start();
            registry.register(instance("logic-server", "logic-1", 9001));

            List<ServiceInstanceEvent> events = new ArrayList<>();
            AutoCloseable watch = registry.watchService("logic-server", events::add);

            // 初始快照同步送达
            assertEquals(1, events.size());
            assertEquals(DiscoveryEventType.ADDED, events.get(0).getType());
            assertEquals("logic-1", events.get(0).getInstance().getId());

            registry.register(instance("logic-server", "logic-2", 9002));
            ServiceInstance updated = instance("logic-server", "logic-2", 9002);
            updated.setWeight(2.0D);
            registry.register(updated);
            registry.unregister(instance("logic-server", "logic-1", 9001));

            assertEquals(4, events.size());
            assertEquals(DiscoveryEventType.ADDED, events.get(1).getType());
            assertEquals("logic-2", events.get(1).getInstance().getId());
            assertEquals(DiscoveryEventType.UPDATED, events.get(2).getType());
            assertEquals(2.0D, events.get(2).getInstance().getWeight());
            assertEquals(DiscoveryEventType.REMOVED, events.get(3).getType());
            assertEquals("logic-1", events.get(3).getInstance().getId());

            watch.close();
            registry.register(instance("logic-server", "logic-3", 9003));
            assertEquals(4, events.size());
            assertEquals(0, registry.getActiveWatchCount());
        }
    }

    @Test
    void reRegisteringIdenticalInstanceEmitsNoEvent() throws Exception {
        try (MemoryRegistry registry = new MemoryRegistry("watch-idempotent")) {
            registry.start();
            List<ServiceInstanceEvent> events = new ArrayList<>();
            registry.watchService("logic-server", events::add);

            ServiceInstance logic = instance("logic-server", "logic-1", 9001);
            registry.register(logic);
            registry.register(logic.copy());

            assertEquals(1, events.size());
        }
    }

    @Test
    void watchServiceNamesEmitsInitialSnapshotAndChanges() throws Exception {
        try (MemoryRegistry registry = new MemoryRegistry("watch-names")) {
            registry.start();
            registry.register(instance("gate-server", "gate-1", 8001));

            List<ServiceNameEvent> events = new ArrayList<>();
            registry.watchServiceNames(events::add);

            assertEquals(1, events.size());
            assertEquals("gate-server", events.get(0).getServiceName());
            assertEquals(DiscoveryEventType.ADDED, events.get(0).getType());

            registry.register(instance("logic-server", "logic-1", 9001));
            // 同名服务再加实例不重复发 ADDED
            registry.register(instance("logic-server", "logic-2", 9002));
            registry.unregister(instance("logic-server", "logic-1", 9001));
            registry.unregister(instance("logic-server", "logic-2", 9002));

            assertEquals(3, events.size());
            assertEquals("logic-server", events.get(1).getServiceName());
            assertEquals(DiscoveryEventType.ADDED, events.get(1).getType());
            assertEquals("logic-server", events.get(2).getServiceName());
            assertEquals(DiscoveryEventType.REMOVED, events.get(2).getType());
        }
    }

    @Test
    void watchersInSameNamespaceSeeOtherRegistryChanges() {
        try (MemoryRegistry server = new MemoryRegistry("watch-cross");
             MemoryRegistry client = new MemoryRegistry("watch-cross")) {
            server.start();
            client.start();

            List<ServiceInstanceEvent> events = new ArrayList<>();
            client.watchService("logic-server", events::add);

            server.register(instance("logic-server", "logic-1", 9001));

            assertEquals(1, events.size());
            assertEquals(DiscoveryEventType.ADDED, events.get(0).getType());
        }
    }

    @Test
    void closeUnregistersOwnInstancesLikeEphemeralNodes() {
        MemoryRegistry server = new MemoryRegistry("ephemeral");
        try (MemoryRegistry observer = new MemoryRegistry("ephemeral")) {
            observer.start();
            server.start();
            server.register(instance("logic-server", "logic-1", 9001));
            assertEquals(1, observer.getInstances("logic-server").size());

            List<ServiceInstanceEvent> events = new ArrayList<>();
            observer.watchService("logic-server", events::add);
            server.close();

            assertTrue(observer.getInstances("logic-server").isEmpty());
            assertEquals(DiscoveryEventType.REMOVED, events.get(events.size() - 1).getType());
        }
    }

    @Test
    void lifecycleGuards() {
        MemoryRegistry registry = new MemoryRegistry("lifecycle");
        assertThrows(RegistryOperationException.class,
                () -> registry.register(instance("logic-server", "logic-1", 9001)));

        registry.start();
        registry.start(); // 幂等
        registry.close();
        registry.close(); // 幂等
        assertTrue(registry.isClosed());
        assertFalse(registry.isStarted());
        assertThrows(RegistryOperationException.class, registry::start);
        assertThrows(RegistryOperationException.class, () -> registry.getInstances("logic-server"));
    }

    @Test
    void factoryExposesMemoryType() {
        assertTrue(RegistryFactory.isAvailable("memory"));
        assertTrue(RegistryFactory.availableTypes().contains("memory"));

        // endpoints 留空时落到默认 namespace
        try (RegistryBundle bundle = RegistryFactory.create(
                RegistryConfig.builder().type("memory").build())) {
            bundle.start();
            bundle.getRegistry().register(instance("logic-server", "logic-1", 9001));
            assertEquals(1, bundle.getDiscovery().getInstances("logic-server").size());
        }
    }
}
