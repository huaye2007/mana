package com.github.huaye2007.mana.registry.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.agent.model.NewCheck;
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.health.model.HealthService;
import com.github.huaye2007.mana.registry.api.ServiceInstance;
import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsulRegistryUnitTest {
    @Test
    void exposesNativeClientForAdvancedConsulSdkUsage() {
        RecordingConsulClient client = new RecordingConsulClient();
        ConsulRegistry registry = new ConsulRegistry(client, disabledPing());

        assertSame(client, registry.getClient());
    }

    @Test
    void registerWrapsServiceInstanceIntoConsulService() {
        RecordingConsulClient client = new RecordingConsulClient();
        Properties properties = disabledPing();
        properties.setProperty(ConsulRegistry.PROP_TAGS, "zone-a, primary");
        ConsulRegistry registry = new ConsulRegistry(client, properties);
        ServiceInstance instance = ServiceInstance.builder()
                .name("room")
                .id("room-1")
                .address("127.0.0.1")
                .port(9001)
                .weight(2.5D)
                .metadata(ConsulRegistry.META_TAGS, "blue")
                .metadata("region", "cn-east")
                .registrationTimeUTC(1234L)
                .build();

        registry.start();
        registry.register(instance);

        NewService consulService = client.registeredService;
        assertEquals("room@@room-1", consulService.getId());
        assertEquals("room", consulService.getName());
        assertEquals("127.0.0.1", consulService.getAddress());
        assertEquals(9001, consulService.getPort());
        assertEquals(List.of("zone-a", "primary", "blue"), consulService.getTags());
        assertEquals("room-1", consulService.getMeta().get(ConsulRegistry.META_ID));
        assertEquals("cn-east", consulService.getMeta().get("region"));
        assertEquals("2.5", consulService.getMeta().get(ConsulRegistry.META_WEIGHT));
        assertEquals("1234", consulService.getMeta().get(ConsulRegistry.META_REGISTRATION_TIME));
    }

    @Test
    void configuredCheckDefaultsDeregisterCriticalServiceAfterSoDeadInstancesAreRemoved() {
        RecordingConsulClient client = new RecordingConsulClient();
        Properties properties = disabledPing();
        properties.setProperty(ConsulRegistry.PROP_CHECK_HTTP, "http://127.0.0.1:9001/health");
        properties.setProperty(ConsulRegistry.PROP_CHECK_INTERVAL, "10s");
        ConsulRegistry registry = new ConsulRegistry(client, properties);

        registry.start();
        registry.register(ServiceInstance.builder()
                .name("room").id("room-1").address("127.0.0.1").port(9001).build());

        NewService.Check check = client.registeredService.getCheck();
        assertNotNull(check);
        assertEquals("1m", check.getDeregisterCriticalServiceAfter());
    }

    @Test
    void explicitDeregisterCriticalServiceAfterIsPreserved() {
        RecordingConsulClient client = new RecordingConsulClient();
        Properties properties = disabledPing();
        properties.setProperty(ConsulRegistry.PROP_CHECK_HTTP, "http://127.0.0.1:9001/health");
        properties.setProperty(ConsulRegistry.PROP_CHECK_DEREGISTER_CRITICAL_SERVICE_AFTER, "5m");
        ConsulRegistry registry = new ConsulRegistry(client, properties);

        registry.start();
        registry.register(ServiceInstance.builder()
                .name("room").id("room-1").address("127.0.0.1").port(9001).build());

        assertEquals("5m", client.registeredService.getCheck().getDeregisterCriticalServiceAfter());
    }

    @Test
    void registerWithoutAnyCheckLeavesNoConsulCheck() {
        RecordingConsulClient client = new RecordingConsulClient();
        ConsulRegistry registry = new ConsulRegistry(client, disabledPing());

        registry.start();
        registry.register(ServiceInstance.builder()
                .name("room").id("room-1").address("127.0.0.1").port(9001).build());

        assertNull(client.registeredService.getCheck());
    }

    @Test
    void heartbeatRegistersTtlCheckBoundToServiceAndKeepsItPassing() {
        RecordingConsulClient client = new RecordingConsulClient();
        Properties properties = disabledPing();
        properties.setProperty(ConsulRegistry.PROP_HEARTBEAT_TTL_SECONDS, "9");
        ConsulRegistry registry = new ConsulRegistry(client, properties);
        ServiceInstance instance = ServiceInstance.builder()
                .name("room").id("room-1").address("127.0.0.1").port(9001).build();

        assertEquals(9L, registry.getHeartbeatTtlSeconds());
        registry.start();
        registry.register(instance);
        try {
            NewCheck check = client.registeredCheck;
            assertNotNull(check);
            assertEquals("room@@room-1:ttl", check.getId());
            assertEquals("room@@room-1", check.getServiceId());
            assertEquals("9s", check.getTtl());
            assertEquals("1m", check.getDeregisterCriticalServiceAfter());
            assertTrue(client.checkPassCount.get() >= 1);
            assertNull(client.deregisteredCheckId);

            registry.unregister(instance);
            assertEquals("room@@room-1:ttl", client.deregisteredCheckId);
        } finally {
            registry.close();
        }
    }

    @Test
    void unregisterUsesServiceScopedConsulId() {
        RecordingConsulClient client = new RecordingConsulClient();
        ConsulRegistry registry = new ConsulRegistry(client, disabledPing());

        registry.start();
        registry.unregister(ServiceInstance.builder()
                .name("room")
                .id("room-1")
                .address("127.0.0.1")
                .port(9001)
                .build());

        assertEquals("room@@room-1", client.deregisteredServiceId);
    }

    @Test
    void getInstancesMapsConsulHealthServiceSnapshot() {
        RecordingConsulClient client = new RecordingConsulClient();
        ConsulRegistry registry = new ConsulRegistry(client, disabledPing());
        HealthService.Service service = new HealthService.Service();
        service.setId("room-1");
        service.setService("room");
        service.setAddress("127.0.0.1");
        service.setPort(9001);
        service.setTags(List.of("blue"));
        service.setMeta(Map.of(
                ConsulRegistry.META_WEIGHT, "2.5",
                ConsulRegistry.META_REGISTRATION_TIME, "1234",
                "region", "cn-east"
        ));
        HealthService healthService = new HealthService();
        healthService.setService(service);
        client.healthServices = List.of(healthService);

        registry.start();
        Collection<ServiceInstance> instances = registry.getInstances("room");

        ServiceInstance instance = instances.iterator().next();
        assertEquals("room", instance.getName());
        assertEquals("room-1", instance.getId());
        assertEquals("127.0.0.1", instance.getAddress());
        assertEquals(9001, instance.getPort());
        assertEquals(2.5D, instance.getWeight());
        assertEquals(1234L, instance.getRegistrationTimeUTC());
        assertEquals("cn-east", instance.getMetadata().get("region"));
        assertEquals("blue", instance.getMetadata().get(ConsulRegistry.META_TAGS));
    }

    @Test
    void getInstancesMapsScopedConsulServiceIdBackToOriginalApiId() {
        RecordingConsulClient client = new RecordingConsulClient();
        ConsulRegistry registry = new ConsulRegistry(client, disabledPing());
        HealthService.Service service = new HealthService.Service();
        service.setId("room@@room-1");
        service.setService("room");
        service.setAddress("127.0.0.1");
        service.setPort(9001);
        service.setMeta(Map.of(ConsulRegistry.META_ID, "room-1"));
        HealthService healthService = new HealthService();
        healthService.setService(service);
        client.healthServices = List.of(healthService);

        registry.start();
        ServiceInstance instance = registry.getInstances("room").iterator().next();

        assertEquals("room-1", instance.getId());
        assertEquals("room-1", instance.getKey());
    }

    @Test
    void watchServiceUsesConsulBlockingQueryIndex() throws Exception {
        RecordingConsulClient client = new RecordingConsulClient();
        Properties properties = disabledPing();
        properties.setProperty(ConsulRegistry.PROP_BLOCKING_QUERY_WAIT_SECONDS, "7");
        ConsulRegistry registry = new ConsulRegistry(client, properties);
        client.healthServices = List.of(healthService("room-1"));

        registry.start();
        AutoCloseable handle = registry.watchService("room", event -> {
        });

        assertTrue(client.blockingQueryStarted.await(2, TimeUnit.SECONDS));
        QueryParams queryParams = client.queryParams.get(1);
        assertEquals(7L, queryParams.getWaitTime());
        assertEquals(1L, queryParams.getIndex());

        handle.close();
        registry.close();
    }

    @Test
    void watchRunsOnVirtualThreadWithoutAThreadCap() throws Exception {
        RecordingConsulClient client = new RecordingConsulClient();
        ConsulRegistry registry = new ConsulRegistry(client, disabledPing());
        client.healthServices = List.of(healthService("room-1"));

        assertEquals(2000L, registry.getWatchShutdownTimeoutMillis());
        registry.start();
        AutoCloseable first = registry.watchService("room", event -> {
        });
        AutoCloseable second = registry.watchService("room", event -> {
        });

        try {
            assertTrue(client.blockingQueryStarted.await(2, TimeUnit.SECONDS));
            assertEquals(2, registry.getActiveWatchCount());
        } finally {
            first.close();
            second.close();
            assertEquals(0, registry.getActiveWatchCount());
            registry.close();
        }
        assertTrue(registry.isClosed());
    }

    @Test
    void rejectsInvalidConsulOptions() {
        Properties invalidShutdownTimeout = disabledPing();
        invalidShutdownTimeout.setProperty(ConsulRegistry.PROP_WATCH_SHUTDOWN_TIMEOUT_MILLIS, "0");

        assertThrows(RegistryOperationException.class,
                () -> new ConsulRegistry(new RecordingConsulClient(), invalidShutdownTimeout));

        Properties invalidHeartbeat = disabledPing();
        invalidHeartbeat.setProperty(ConsulRegistry.PROP_HEARTBEAT_TTL_SECONDS, "0");

        assertThrows(RegistryOperationException.class,
                () -> new ConsulRegistry(new RecordingConsulClient(), invalidHeartbeat));
    }

    private static Properties disabledPing() {
        Properties properties = new Properties();
        properties.setProperty(ConsulRegistry.PROP_PING_ON_START, "false");
        return properties;
    }

    private static HealthService healthService(String id) {
        HealthService.Service service = new HealthService.Service();
        service.setId(id);
        service.setService("room");
        service.setAddress("127.0.0.1");
        service.setPort(9001);
        HealthService healthService = new HealthService();
        healthService.setService(service);
        return healthService;
    }

    private static final class RecordingConsulClient extends ConsulClient {
        private NewService registeredService;
        private String deregisteredServiceId;
        private volatile NewCheck registeredCheck;
        private volatile String deregisteredCheckId;
        private final AtomicInteger checkPassCount = new AtomicInteger();
        private List<HealthService> healthServices = List.of();
        private final List<QueryParams> queryParams = new CopyOnWriteArrayList<>();
        private final CountDownLatch blockingQueryStarted = new CountDownLatch(1);

        private RecordingConsulClient() {
            super("127.0.0.1", 8500);
        }

        @Override
        public Response<Void> agentServiceRegister(NewService newService) {
            this.registeredService = newService;
            return new Response<>(null, 1L, true, 0L);
        }

        @Override
        public Response<Void> agentServiceDeregister(String serviceId) {
            this.deregisteredServiceId = serviceId;
            return new Response<>(null, 1L, true, 0L);
        }

        @Override
        public Response<Void> agentCheckRegister(NewCheck newCheck) {
            this.registeredCheck = newCheck;
            return new Response<>(null, 1L, true, 0L);
        }

        @Override
        public Response<Void> agentCheckPass(String checkId) {
            this.checkPassCount.incrementAndGet();
            return new Response<>(null, 1L, true, 0L);
        }

        @Override
        public Response<Void> agentCheckDeregister(String checkId) {
            this.deregisteredCheckId = checkId;
            return new Response<>(null, 1L, true, 0L);
        }

        @Override
        public Response<List<HealthService>> getHealthServices(
                String serviceName,
                boolean onlyPassing,
                QueryParams queryParams
        ) {
            this.queryParams.add(queryParams);
            if (queryParams.getIndex() > 0) {
                blockingQueryStarted.countDown();
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return new Response<>(healthServices, 1L, true, 0L);
        }
    }
}
