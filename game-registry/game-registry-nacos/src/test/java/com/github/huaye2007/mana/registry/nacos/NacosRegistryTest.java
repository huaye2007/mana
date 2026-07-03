package com.github.huaye2007.mana.registry.nacos;

import com.github.huaye2007.mana.registry.api.DiscoveryEventType;
import com.github.huaye2007.mana.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "game.registry.integration.nacos", matches = "true")
class NacosRegistryTest {
    private static final String DEFAULT_ENDPOINTS = "127.0.0.1:8848";
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    private static final String DEFAULT_CLUSTER = "DEFAULT";

    @Test
    void testRegistryLifecycle() {
        String endpoints = System.getProperty("game.registry.integration.nacos.endpoints", DEFAULT_ENDPOINTS);
        String group = System.getProperty("game.registry.integration.nacos.group", DEFAULT_GROUP);
        String cluster = System.getProperty("game.registry.integration.nacos.cluster", DEFAULT_CLUSTER);
        Properties properties = new Properties();
        properties.setProperty(NacosRegistry.META_GROUP, group);
        properties.setProperty(NacosRegistry.META_CLUSTER, cluster);
        NacosRegistry registry = new NacosRegistry(endpoints, properties);
        registry.start();

        String serviceName = "nacos-test-service-" + UUID.randomUUID();
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId("nacos-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(8080);
        instance.setMetadata(Map.of(
                NacosRegistry.META_GROUP, group,
                NacosRegistry.META_CLUSTER, cluster
        ));

        try {
            registry.register(instance);
            try {
                Collection<ServiceInstance> instances = registry.getInstances(serviceName);
                assertEquals(1, instances.size());
                assertEquals("nacos-1", instances.iterator().next().getId());
            } finally {
                unregisterQuietly(registry, instance);
            }

            Collection<ServiceInstance> afterUnregister = registry.getInstances(serviceName);
            assertTrue(afterUnregister.isEmpty());
        } finally {
            registry.close();
        }
    }

    @Test
    void serviceInstanceWatchObservesAddAndRemoveWithRealNacos() throws Exception {
        Properties properties = nacosProperties();
        String endpoints = System.getProperty("game.registry.integration.nacos.endpoints", DEFAULT_ENDPOINTS);
        String group = properties.getProperty(NacosRegistry.META_GROUP);
        String cluster = properties.getProperty(NacosRegistry.META_CLUSTER);
        NacosRegistry registry = new NacosRegistry(endpoints, properties);

        String serviceName = "nacos-instance-watch-test-" + UUID.randomUUID();
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId("nacos-watch-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(8082);
        instance.setMetadata(Map.of(
                NacosRegistry.META_GROUP, group,
                NacosRegistry.META_CLUSTER, cluster
        ));

        registry.start();
        try {
            CountDownLatch added = new CountDownLatch(1);
            CountDownLatch removed = new CountDownLatch(1);
            AutoCloseable handle = registry.watchService(serviceName, event -> {
                if (!instance.getId().equals(event.getInstance().getId())) {
                    return;
                }
                if (event.getType() == DiscoveryEventType.ADDED) {
                    added.countDown();
                } else if (event.getType() == DiscoveryEventType.REMOVED) {
                    removed.countDown();
                }
            });
            try {
                registry.register(instance);
                assertTrue(added.await(10, TimeUnit.SECONDS));
                registry.unregister(instance);
                assertTrue(removed.await(10, TimeUnit.SECONDS));
            } finally {
                handle.close();
            }
        } finally {
            unregisterQuietly(registry, instance);
            registry.close();
        }
    }

    @Test
    void serviceNameWatchObservesAddAndRemoveWithRealNacos() throws Exception {
        Properties properties = nacosProperties();
        properties.setProperty(NacosRegistry.PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS, "100");

        assertServiceNameWatchObservesAddAndRemove(properties);
    }

    private void assertServiceNameWatchObservesAddAndRemove(Properties properties) throws Exception {
        String endpoints = System.getProperty("game.registry.integration.nacos.endpoints", DEFAULT_ENDPOINTS);
        String group = properties.getProperty(NacosRegistry.META_GROUP);
        String cluster = properties.getProperty(NacosRegistry.META_CLUSTER);
        NacosRegistry registry = new NacosRegistry(endpoints, properties);

        String serviceName = "nacos-name-watch-test-" + UUID.randomUUID();
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId("nacos-name-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(8081);
        instance.setMetadata(Map.of(
                NacosRegistry.META_GROUP, group,
                NacosRegistry.META_CLUSTER, cluster
        ));

        registry.start();
        try {
            assertTrue(awaitUntil(() -> !registry.getServiceNames().contains(serviceName)));

            CountDownLatch added = new CountDownLatch(1);
            CountDownLatch removed = new CountDownLatch(1);
            AutoCloseable handle = registry.watchServiceNames(event -> {
                if (!serviceName.equals(event.getServiceName())) {
                    return;
                }
                if (event.getType() == DiscoveryEventType.ADDED) {
                    added.countDown();
                } else if (event.getType() == DiscoveryEventType.REMOVED) {
                    removed.countDown();
                }
            });
            try {
                registry.register(instance);
                assertTrue(added.await(10, TimeUnit.SECONDS));
                registry.unregister(instance);
                assertTrue(removed.await(10, TimeUnit.SECONDS));
            } finally {
                handle.close();
            }
        } finally {
            unregisterQuietly(registry, instance);
            registry.close();
        }
    }

    private Properties nacosProperties() {
        String group = System.getProperty("game.registry.integration.nacos.group", DEFAULT_GROUP);
        String cluster = System.getProperty("game.registry.integration.nacos.cluster", DEFAULT_CLUSTER);
        Properties properties = new Properties();
        properties.setProperty(NacosRegistry.META_GROUP, group);
        properties.setProperty(NacosRegistry.META_CLUSTER, cluster);
        return properties;
    }

    private void unregisterQuietly(NacosRegistry registry, ServiceInstance instance) {
        try {
            registry.unregister(instance);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(100L);
        }
        return condition.getAsBoolean();
    }
}
