package cn.managame.registry.api;

import cn.managame.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceInstanceTest {
    @Test
    void equalityIncludesRegistrationTime() {
        ServiceInstance first = instance(100L);
        ServiceInstance second = instance(200L);

        assertNotEquals(first, second);
        assertNotEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void equalityMatchesAllModelFields() {
        ServiceInstance first = instance(100L);
        ServiceInstance second = instance(100L);

        Set<ServiceInstance> instances = new HashSet<>();
        instances.add(first);

        assertEquals(first, second);
        assertTrue(instances.contains(second));
    }

    @Test
    void newInstancesDefaultRegistrationTimeToCreationTime() {
        long before = System.currentTimeMillis();

        ServiceInstance empty = new ServiceInstance();
        ServiceInstance constructed = new ServiceInstance("room-service", "room-1", "127.0.0.1", 9000);
        ServiceInstance built = ServiceInstance.builder()
                .name("room-service")
                .id("room-1")
                .address("127.0.0.1")
                .port(9000)
                .build();

        long after = System.currentTimeMillis();

        assertBetween(before, after, empty.getRegistrationTimeUTC());
        assertBetween(before, after, constructed.getRegistrationTimeUTC());
        assertBetween(before, after, built.getRegistrationTimeUTC());
    }

    @Test
    void copyCreatesDefensiveMetadataCopy() {
        ServiceInstance source = ServiceInstance.builder()
                .name("room-service")
                .id("room-1")
                .address("127.0.0.1")
                .port(9000)
                .weight(2.0D)
                .healthy(false)
                .metadata(Map.of("zone", "a"))
                .registrationTimeUTC(100L)
                .build();

        ServiceInstance copy = source.copy();
        copy.getMetadata().put("zone", "b");

        assertEquals(source, ServiceInstance.copyOf(source));
        assertEquals("a", source.getMetadata().get("zone"));
        assertEquals("b", copy.getMetadata().get("zone"));
    }

    @Test
    void builderBuildReturnsSnapshot() {
        ServiceInstance.Builder builder = ServiceInstance.builder()
                .name("room-service")
                .id("room-1")
                .address("127.0.0.1")
                .port(9000)
                .metadata("zone", "a");

        ServiceInstance first = builder.build();
        ServiceInstance second = builder.metadata("zone", "b").build();

        assertEquals("a", first.getMetadata().get("zone"));
        assertEquals("b", second.getMetadata().get("zone"));
    }

    @Test
    void rejectsNullMetadataEntriesAtMutationBoundary() {
        ServiceInstance instance = new ServiceInstance();
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "a");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("zone", null);

        assertThrows(RegistryOperationException.class, () -> instance.setMetadata(nullKey));
        assertThrows(RegistryOperationException.class, () -> instance.setMetadata(nullValue));
        assertThrows(RegistryOperationException.class,
                () -> ServiceInstance.builder().metadata(null, "a"));
        assertThrows(RegistryOperationException.class,
                () -> ServiceInstance.builder().metadata("zone", null));
    }

    @Test
    void keyFallsBackToAddressAndPortWhenIdIsBlank() {
        ServiceInstance instance = instance(100L);
        instance.setId(" ");

        assertEquals("127.0.0.1:9000", instance.getKey());
    }

    private ServiceInstance instance(long registrationTimeUTC) {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("room-service");
        instance.setId("room-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(9000);
        instance.setRegistrationTimeUTC(registrationTimeUTC);
        return instance;
    }

    private void assertBetween(long lowerInclusive, long upperInclusive, long actual) {
        assertTrue(actual >= lowerInclusive, "expected " + actual + " to be >= " + lowerInclusive);
        assertTrue(actual <= upperInclusive, "expected " + actual + " to be <= " + upperInclusive);
    }
}
