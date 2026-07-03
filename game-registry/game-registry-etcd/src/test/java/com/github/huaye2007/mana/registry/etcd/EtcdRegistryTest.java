package com.github.huaye2007.mana.registry.etcd;

import com.github.huaye2007.mana.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "game.registry.integration.etcd", matches = "true")
class EtcdRegistryTest {
    private static final String DEFAULT_ENDPOINTS = "http://127.0.0.1:2379";
    private static final String DEFAULT_BASE_PATH = "/services";
    private static final long DEFAULT_TTL_SECONDS = 10L;

    @Test
    void testRegistryLifecycle() {
        String endpoints = System.getProperty("game.registry.integration.etcd.endpoints", DEFAULT_ENDPOINTS);
        String basePath = System.getProperty("game.registry.integration.etcd.basePath", DEFAULT_BASE_PATH);
        long ttlSeconds = Long.getLong("game.registry.integration.etcd.ttlSeconds", DEFAULT_TTL_SECONDS);
        EtcdRegistry registry = new EtcdRegistry(endpoints, basePath, ttlSeconds);
        registry.start();

        String serviceName = "etcd-test-service-" + UUID.randomUUID();
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId("etcd-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(8080);

        try {
            registry.register(instance);
            try {
                Collection<ServiceInstance> instances = registry.getInstances(serviceName);
                assertEquals(1, instances.size());
                assertEquals("etcd-1", instances.iterator().next().getId());
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
    void closeRevokesLeaseAndRemovesInstancesForOtherDiscovery() throws Exception {
        String endpoints = System.getProperty("game.registry.integration.etcd.endpoints", DEFAULT_ENDPOINTS);
        String basePath = System.getProperty("game.registry.integration.etcd.basePath", DEFAULT_BASE_PATH);
        long ttlSeconds = Long.getLong("game.registry.integration.etcd.ttlSeconds", DEFAULT_TTL_SECONDS);
        EtcdRegistry owner = new EtcdRegistry(endpoints, basePath, ttlSeconds);
        EtcdRegistry observer = new EtcdRegistry(endpoints, basePath, ttlSeconds);

        String serviceName = "etcd-lease-close-test-" + UUID.randomUUID();
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId("etcd-lease-1");
        instance.setAddress("127.0.0.1");
        instance.setPort(8081);

        owner.start();
        observer.start();
        try {
            owner.register(instance);
            assertTrue(awaitUntil(() -> containsInstance(observer.getInstances(serviceName), instance.getId())));

            owner.close();
            assertTrue(awaitUntil(() -> !containsInstance(observer.getInstances(serviceName), instance.getId())));
        } finally {
            unregisterQuietly(observer, instance);
            owner.close();
            observer.close();
        }
    }

    private void unregisterQuietly(EtcdRegistry registry, ServiceInstance instance) {
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

    private boolean containsInstance(Collection<ServiceInstance> instances, String id) {
        for (ServiceInstance instance : instances) {
            if (id.equals(instance.getId())) {
                return true;
            }
        }
        return false;
    }
}
