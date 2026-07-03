package com.github.huaye2007.mana.registry.api;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveryTest {
    @Test
    void defaultWatchMethodsRejectInvalidArgumentsBeforeUnsupported() {
        Discovery discovery = new MutableDiscovery();

        assertThrows(RegistryOperationException.class, () -> discovery.watchService(" ", event -> {
        }));
        assertThrows(RegistryOperationException.class, () -> discovery.watchService(" room-service ", event -> {
        }));
        assertThrows(RegistryOperationException.class, () -> discovery.watchService("room/service", event -> {
        }));
        assertThrows(RegistryOperationException.class, () -> discovery.watchService("room-service", null));
        assertThrows(RegistryOperationException.class, () -> discovery.watchServiceNames(null));
    }

    @Test
    void serviceInstanceEventDefensivelyCopiesInstance() {
        ServiceInstance source = instance("room-1");
        source.setMetadata(java.util.Map.of("zone", "a"));

        ServiceInstanceEvent event = new ServiceInstanceEvent(
                "room-service",
                DiscoveryEventType.ADDED,
                source
        );
        source.setAddress("10.0.0.1");

        ServiceInstance first = event.getInstance();
        first.setAddress("10.0.0.2");
        first.getMetadata().put("zone", "b");
        ServiceInstance second = event.getInstance();

        assertNotSame(first, second);
        assertEquals("127.0.0.1", second.getAddress());
        assertEquals("a", second.getMetadata().get("zone"));
    }

    private static ServiceInstance instance(String id) {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("room-service");
        instance.setId(id);
        instance.setAddress("127.0.0.1");
        instance.setPort(9000);
        return instance;
    }

    private static class MutableDiscovery implements Discovery {
        private Collection<ServiceInstance> instances;

        MutableDiscovery(ServiceInstance... instances) {
            this.instances = List.of(instances);
        }

        @Override
        public Collection<ServiceInstance> getInstances(String serviceName) {
            return instances;
        }

        @Override
        public Collection<String> getServiceNames() {
            return List.of("room-service");
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    }
}
