package com.github.huaye2007.mana.registry.api;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveryEventsTest {
    @Test
    void serviceInstanceEventRejectsInvalidShape() {
        ServiceInstance instance = instance();

        assertThrows(RegistryOperationException.class,
                () -> new ServiceInstanceEvent(" ", DiscoveryEventType.ADDED, instance));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceInstanceEvent(" room-service ", DiscoveryEventType.ADDED, instance));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceInstanceEvent("room/service", DiscoveryEventType.ADDED, instance));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceInstanceEvent("room-service", null, instance));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceInstanceEvent("room-service", DiscoveryEventType.ADDED, null));

        ServiceInstance mismatched = instance();
        mismatched.setName("match-service");
        assertThrows(RegistryOperationException.class,
                () -> new ServiceInstanceEvent("room-service", DiscoveryEventType.ADDED, mismatched));
    }

    @Test
    void serviceNameEventRejectsInvalidShape() {
        assertThrows(RegistryOperationException.class,
                () -> new ServiceNameEvent(" ", DiscoveryEventType.ADDED));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceNameEvent(" room-service ", DiscoveryEventType.ADDED));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceNameEvent("room/service", DiscoveryEventType.ADDED));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceNameEvent("room-service", null));
        assertThrows(RegistryOperationException.class,
                () -> new ServiceNameEvent("room-service", DiscoveryEventType.UPDATED));

        ServiceNameEvent event = new ServiceNameEvent("room-service", DiscoveryEventType.REMOVED);
        assertEquals("room-service", event.getServiceName());
        assertEquals(DiscoveryEventType.REMOVED, event.getType());
    }

    private ServiceInstance instance() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("room-service");
        instance.setId("room-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(9000);
        return instance;
    }
}
