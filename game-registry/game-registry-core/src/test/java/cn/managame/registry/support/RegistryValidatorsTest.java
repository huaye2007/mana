package cn.managame.registry.support;

import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryValidatorsTest {
    @Test
    void validatesHealthyServiceInstanceShape() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("room-service");
        instance.setAddress("127.0.0.1");
        instance.setPort(9000);

        assertDoesNotThrow(() -> RegistryValidators.validateInstance(instance));
    }

    @Test
    void rejectsInvalidServiceInstanceShape() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("room-service");
        instance.setAddress("127.0.0.1");
        instance.setPort(0);

        assertThrows(RegistryOperationException.class, () -> RegistryValidators.validateInstance(instance));
    }

    @Test
    void rejectsNegativeRegistrationTime() {
        ServiceInstance instance = validInstance(new ServiceInstance());
        instance.setRegistrationTimeUTC(-1L);

        assertThrows(RegistryOperationException.class, () -> RegistryValidators.validateInstance(instance));
    }

    @Test
    void rejectsPathLikeServiceIdentityFields() {
        ServiceInstance serviceNameWithSlash = validInstance(new ServiceInstance());
        serviceNameWithSlash.setName("room/service");

        ServiceInstance idWithSlash = validInstance(new ServiceInstance());
        idWithSlash.setId("room/1");

        ServiceInstance fallbackKeyAddressWithSlash = validInstance(new ServiceInstance());
        fallbackKeyAddressWithSlash.setAddress("10.0.0.8/path");

        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateServiceName("room/service"));
        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateInstance(serviceNameWithSlash));
        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateInstance(idWithSlash));
        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateInstance(fallbackKeyAddressWithSlash));
    }

    @Test
    void rejectsIdentityFieldsWithSurroundingWhitespace() {
        ServiceInstance serviceNameWithWhitespace = validInstance(new ServiceInstance());
        serviceNameWithWhitespace.setName(" room-service ");

        ServiceInstance idWithWhitespace = validInstance(new ServiceInstance());
        idWithWhitespace.setId(" room-1 ");

        ServiceInstance addressWithWhitespace = validInstance(new ServiceInstance());
        addressWithWhitespace.setAddress(" 127.0.0.1 ");

        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateServiceName(" room-service "));
        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateInstance(serviceNameWithWhitespace));
        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateInstance(idWithWhitespace));
        assertThrows(RegistryOperationException.class,
                () -> RegistryValidators.validateInstance(addressWithWhitespace));
    }

    @Test
    void rejectsBlankEndpointEntries() {
        assertThrows(RegistryOperationException.class, () -> RegistryValidators.validateEndpoints("127.0.0.1:2181, "));
    }

    @Test
    void normalizesEndpointEntries() {
        assertEquals(
                "127.0.0.1:2181,127.0.0.2:2181",
                RegistryValidators.normalizeEndpoints(" 127.0.0.1:2181, 127.0.0.2:2181 ")
        );
    }

    @Test
    void normalizesBasePath() {
        assertEquals("/services", RegistryValidators.normalizeBasePath(" /services/// "));
        assertEquals("/", RegistryValidators.normalizeBasePath(" / "));
        assertThrows(RegistryOperationException.class, () -> RegistryValidators.normalizeBasePath("services"));
    }

    @Test
    void rejectsNullMetadataValues() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("room-service");
        instance.setAddress("127.0.0.1");
        instance.setPort(9000);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("zone", null);

        assertThrows(RegistryOperationException.class, () -> instance.setMetadata(metadata));
    }

    @Test
    void normalizesNullMetadataToEmptyMap() {
        ServiceInstance instance = new ServiceInstance();
        instance.setMetadata(null);

        assertNotNull(instance.getMetadata());
    }

    @Test
    void rejectsNullListeners() {
        assertThrows(RegistryOperationException.class, () -> RegistryValidators.validateListener(null));
    }

    @Test
    void setMetadataCopiesInputMap() {
        ServiceInstance instance = new ServiceInstance();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("zone", "a");

        instance.setMetadata(metadata);
        metadata.put("zone", "b");

        assertEquals("a", instance.getMetadata().get("zone"));
        assertDoesNotThrow(() -> RegistryValidators.validateInstance(validInstance(instance)));
    }

    private ServiceInstance validInstance(ServiceInstance instance) {
        instance.setName("room-service");
        instance.setAddress("127.0.0.1");
        instance.setPort(9000);
        return instance;
    }
}
