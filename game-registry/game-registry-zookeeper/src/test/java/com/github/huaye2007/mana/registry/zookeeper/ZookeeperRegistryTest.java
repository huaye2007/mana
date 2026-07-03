package com.github.huaye2007.mana.registry.zookeeper;

import com.github.huaye2007.mana.registry.api.DiscoveryEventType;
import com.github.huaye2007.mana.registry.api.ServiceInstance;
import com.github.huaye2007.mana.registry.api.ServiceInstanceEvent;
import com.github.huaye2007.mana.registry.api.ServiceNameEvent;
import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.TestingZooKeeperServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "game.registry.integration.zookeeper", matches = "true")
class ZookeeperRegistryTest {
    private static boolean originalEmbeddedAdapterAvailable;
    private static boolean embeddedAdapterOverridden;
    private TestingServer testingServer;
    private ZookeeperRegistry registry;
    private CuratorFramework client;

    @BeforeAll
    static void prepareEmbeddedZookeeper() throws Exception {
        verifySelectorAvailable();
        Field field = TestingZooKeeperServer.class.getDeclaredField("hasZooKeeperServerEmbedded");
        field.setAccessible(true);
        originalEmbeddedAdapterAvailable = field.getBoolean(null);
        field.setBoolean(null, false);
        embeddedAdapterOverridden = true;
    }

    @AfterAll
    static void restoreEmbeddedZookeeperMode() throws Exception {
        if (!embeddedAdapterOverridden) {
            return;
        }
        Field field = TestingZooKeeperServer.class.getDeclaredField("hasZooKeeperServerEmbedded");
        field.setAccessible(true);
        field.setBoolean(null, originalEmbeddedAdapterAvailable);
    }

    @BeforeEach
    void setUp() throws Exception {
        try {
            testingServer = new TestingServer();
            client = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), new RetryOneTime(1));
            client.start();
            registry = new ZookeeperRegistry(client, "/services");
            registry.start();
        } catch (Exception e) {
            closeQuietly(registry);
            closeQuietly(client);
            closeQuietly(testingServer);
            registry = null;
            client = null;
            testingServer = null;
            throw new TestAbortedException("Embedded ZooKeeper unavailable in this environment", e);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (registry != null) {
            registry.close();
        }
        if (client != null) {
            client.close();
        }
        if (testingServer != null) {
            testingServer.close();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static void verifySelectorAvailable() {
        try (Selector ignored = Selector.open()) {
        } catch (IOException e) {
            throw new TestAbortedException("Embedded ZooKeeper requires a working loopback selector", e);
        }
    }

    @Test
    void testRegisterAndDiscover() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("game-server");
        instance.setId("server-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(8080);
        registry.register(instance);
        Collection<ServiceInstance> instances = registry.getInstances("game-server");

        assertEquals(1, instances.size());
        ServiceInstance fetched = instances.iterator().next();
        assertEquals("game-server", fetched.getName());
        assertEquals("server-1", fetched.getId());
    }

    @Test
    void testUnregister() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("auth-server");
        instance.setId("auth-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(9090);

        registry.register(instance);
        registry.unregister(instance);
        Collection<ServiceInstance> instances = registry.getInstances("auth-server");
        assertTrue(instances.isEmpty());
    }

    @Test
    void testMetadataPreserved() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("meta-server");
        instance.setId("meta-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(7070);
        instance.setWeight(2.5);
        instance.setHealthy(true);
        instance.setMetadata(Map.of("region", "us-east", "version", "1.0"));

        registry.register(instance);
        Collection<ServiceInstance> instances = registry.getInstances("meta-server");

        assertEquals(1, instances.size());
        ServiceInstance fetched = instances.iterator().next();
        assertEquals("meta-server", fetched.getName());
        assertEquals("meta-1", fetched.getId());
        assertEquals(2.5, fetched.getWeight());
        assertTrue(fetched.isHealthy());
        assertEquals("us-east", fetched.getMetadata().get("region"));
        assertEquals("1.0", fetched.getMetadata().get("version"));
    }

    @Test
    void testPayloadCopiesMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("region", "us-east");
        ZookeeperRegistry.ServiceInstancePayload payload =
                new ZookeeperRegistry.ServiceInstancePayload(2.0D, true, metadata);

        metadata.put("region", "changed");
        Map<String, String> returned = payload.getMetadata();
        returned.put("region", "mutated");

        assertEquals("us-east", payload.getMetadata().get("region"));

        payload.setMetadata(null);
        assertTrue(payload.getMetadata().isEmpty());
    }

    @Test
    void testServiceInstanceGetKey() {
        ServiceInstance withId = new ServiceInstance("test", "id-1", "127.0.0.1", 8080);
        assertEquals("id-1", withId.getKey());

        ServiceInstance withoutId = new ServiceInstance();
        withoutId.setAddress("192.168.1.1");
        withoutId.setPort(9090);
        assertEquals("192.168.1.1:9090", withoutId.getKey());
    }

    @Test
    void testWatchService() throws Exception {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("watch-server");
        instance.setId("watch-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(5050);

        CountDownLatch addLatch = new CountDownLatch(1);
        AtomicReference<ServiceInstanceEvent> capturedEvent = new AtomicReference<>();

        AutoCloseable watchHandle = registry.watchService("watch-server", event -> {
            if (event.getType() == DiscoveryEventType.ADDED) {
                capturedEvent.set(event);
                addLatch.countDown();
            }
        });

        try {
            registry.register(instance);
            assertTrue(addLatch.await(5, TimeUnit.SECONDS));
            ServiceInstanceEvent event = capturedEvent.get();
            assertNotNull(event);
            assertEquals("watch-1", event.getInstance().getId());
        } finally {
            watchHandle.close();
        }
    }

    @Test
    void testDiscoverySnapshotsAreUnmodifiable() {
        registry.register(instance("snapshot-server", "snapshot-1", 8201));

        Collection<ServiceInstance> instances = registry.getInstances("snapshot-server");
        Collection<String> names = registry.getServiceNames();

        assertThrows(UnsupportedOperationException.class, instances::clear);
        assertThrows(UnsupportedOperationException.class, names::clear);
    }

    @Test
    void testDiscoverySnapshotsAreSorted() {
        registry.register(instance("sorted-server", "sorted-2", 8402));
        registry.register(instance("sorted-server", "sorted-1", 8401));
        registry.register(instance("zeta-server", "zeta-1", 8501));
        registry.register(instance("alpha-server", "alpha-1", 8502));

        assertEquals(List.of("sorted-1", "sorted-2"), registry.getInstances("sorted-server").stream()
                .map(ServiceInstance::getId)
                .toList());
        assertEquals(List.of("alpha-server", "sorted-server", "zeta-server"),
                registry.getServiceNames().stream()
                        .filter(name -> name.endsWith("-server") && !name.equals("snapshot-server"))
                        .toList());
    }

    @Test
    void testRejectsInvalidInputs() {
        ServiceInstance invalid = new ServiceInstance();
        invalid.setName("invalid-server");
        invalid.setAddress("127.0.0.1");
        invalid.setPort(0);

        assertThrows(RegistryOperationException.class, () -> registry.register(invalid));
        assertThrows(RegistryOperationException.class, () -> registry.getInstances(" "));
        assertThrows(RegistryOperationException.class, () -> registry.watchService(" ", event -> {
        }));
        assertThrows(RegistryOperationException.class, () -> registry.watchService("invalid-server", null));
        assertThrows(RegistryOperationException.class, () -> registry.watchServiceNames(null));
    }

    @Test
    void testRejectsOperationsBeforeStart() {
        ZookeeperRegistry notStarted = new ZookeeperRegistry(client, "/not-started-services");
        ServiceInstance valid = instance("not-started-server", "not-started-1", 8181);

        assertThrows(RegistryOperationException.class, () -> notStarted.register(valid));
        assertThrows(RegistryOperationException.class, () -> notStarted.unregister(valid));
        assertThrows(RegistryOperationException.class, () -> notStarted.getInstances("not-started-server"));
        assertThrows(RegistryOperationException.class, notStarted::getServiceNames);
        assertThrows(RegistryOperationException.class, () -> notStarted.watchService("not-started-server", event -> {
        }));
        assertThrows(RegistryOperationException.class, () -> notStarted.watchServiceNames(event -> {
        }));
    }

    @Test
    void testCloseIsIdempotent() {
        registry.close();
        registry.close();
        registry = null;
    }

    @Test
    void testRejectsStartAfterClose() {
        registry.close();

        assertThrows(RegistryOperationException.class, registry::start);
        registry = null;
    }

    @Test
    void testRejectsOperationsAfterCloseWithClosedMessage() {
        registry.close();

        RegistryOperationException failure = assertThrows(
                RegistryOperationException.class,
                () -> registry.register(instance("closed-server", "closed-1", 8301))
        );

        assertEquals("Zookeeper registry has been closed", failure.getMessage());
        registry = null;
    }

    @Test
    void testCloseReportsWatchFailuresAndClearsHandles() throws Exception {
        ConcurrentMap<Long, AutoCloseable> handles = watchHandles(registry);
        handles.put(1L, () -> {
            throw new IllegalStateException("watch close failed");
        });

        RegistryOperationException failure = assertThrows(RegistryOperationException.class, registry::close);

        assertEquals("Failed to close zookeeper watch", failure.getMessage());
        assertTrue(handles.isEmpty());
        registry.close();
        registry = null;
    }

    @Test
    void testRejectsInvalidProductionClientOptions() {
        assertThrows(
                RegistryOperationException.class,
                () -> new ZookeeperRegistry((CuratorFramework) null, "/services")
        );

        Properties missingAuth = new Properties();
        missingAuth.setProperty(ZookeeperRegistry.PROP_AUTH_SCHEME, "digest");

        assertThrows(RegistryOperationException.class,
                () -> new ZookeeperRegistry(testingServer.getConnectString(), "/services", missingAuth));

        Properties invalidRetry = new Properties();
        invalidRetry.setProperty(ZookeeperRegistry.PROP_RETRY_MAX_RETRIES, "0");

        assertThrows(RegistryOperationException.class,
                () -> new ZookeeperRegistry(testingServer.getConnectString(), "/services", invalidRetry));
    }

    private ServiceInstance instance(String serviceName, String id, int port) {
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId(id);
        instance.setAddress("127.0.0.1");
        instance.setPort(port);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Long, AutoCloseable> watchHandles(ZookeeperRegistry registry) throws Exception {
        Field field = ZookeeperRegistry.class.getDeclaredField("watchHandles");
        field.setAccessible(true);
        return (ConcurrentMap<Long, AutoCloseable>) field.get(registry);
    }
}
