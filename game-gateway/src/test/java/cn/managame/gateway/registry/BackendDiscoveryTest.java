package cn.managame.gateway.registry;

import cn.managame.gateway.router.BackendRouterManager;
import cn.managame.gateway.router.ConsistentHashRouter;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BackendDiscoveryTest {
    @Test void reconcilesAddUpdateRemoveAndClose() {
        ServiceRegistry registry = RegistryFactory.startRegistry(RegistryConfig.builder()
                .type("memory").endpoints("gateway-test-" + UUID.randomUUID()).build());
        BackendRouterManager routers = new BackendRouterManager(new ConsistentHashRouter());
        RecordingConnector connector = new RecordingConnector();
        BackendDiscovery discovery = new BackendDiscovery(registry, "game", routers, connector);
        ServiceInstance first = instance("a", 9000, true);
        ServiceInstance moved = instance("a", 9001, true);
        ServiceInstance unhealthy = instance("a", 9001, false);
        try {
            discovery.start();
            registry.register(first);
            assertEquals(1, routers.instanceCount());
            assertEquals(List.of(first), connector.connected);
            registry.register(moved);
            assertEquals(List.of(first), connector.disconnected);
            assertEquals(moved, routers.get("a"));
            registry.register(unhealthy);
            assertEquals(0, routers.instanceCount());
            assertEquals(moved, connector.disconnected.getLast());
            discovery.close();
        } finally {
            registry.close();
        }
    }

    private static ServiceInstance instance(String id, int port, boolean healthy) {
        return ServiceInstance.builder().name("game").id(id).address("127.0.0.1").port(port).healthy(healthy).build();
    }

    private static final class RecordingConnector implements BackendConnector {
        final List<ServiceInstance> connected = new ArrayList<>();
        final List<ServiceInstance> disconnected = new ArrayList<>();
        @Override public void connectBackend(ServiceInstance instance) { connected.add(instance); }
        @Override public void disconnectBackend(ServiceInstance instance) { disconnected.add(instance); }
    }
}
