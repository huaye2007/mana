package com.github.huaye2007.mana.registry.consul;

import com.github.huaye2007.mana.registry.api.DiscoveryEventType;
import com.github.huaye2007.mana.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Collection;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "game.registry.integration.consul", matches = "true")
class ConsulRegistryTest {
    private static final String DEFAULT_ENDPOINTS = "127.0.0.1:8500";

    @Test
    void registerDiscoverWatchAndUnregisterWithRealConsul() throws Exception {
        String endpoints = System.getProperty("game.registry.integration.consul.endpoints", DEFAULT_ENDPOINTS);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String serviceName = "game-registry-it-" + suffix;
        ServiceInstance instance = ServiceInstance.builder()
                .name(serviceName)
                .id(serviceName + "-1")
                .address("127.0.0.1")
                .port(19001)
                .registrationTimeUTC(System.currentTimeMillis())
                .build();
        Properties properties = new Properties();
        properties.setProperty(ConsulRegistry.PROP_BLOCKING_QUERY_WAIT_SECONDS, "1");
        ConsulRegistry registry = new ConsulRegistry(endpoints, properties);

        registry.start();
        try {
            registry.register(instance);
            assertTrue(awaitUntil(() -> containsInstance(registry.getInstances(serviceName), instance.getId())));

            CountDownLatch removed = new CountDownLatch(1);
            AutoCloseable handle = registry.watchService(serviceName, event -> {
                if (event.getType() == DiscoveryEventType.REMOVED
                        && instance.getId().equals(event.getInstance().getId())) {
                    removed.countDown();
                }
            });
            try {
                registry.unregister(instance);
                assertTrue(removed.await(10, TimeUnit.SECONDS));
            } finally {
                handle.close();
            }
            assertTrue(awaitUntil(() -> !containsInstance(registry.getInstances(serviceName), instance.getId())));
        } finally {
            try {
                registry.unregister(instance);
            } catch (RuntimeException ignored) {
            }
            registry.close();
        }
    }

    @Test
    void serviceNameWatchObservesAddAndRemoveWithRealConsul() throws Exception {
        String endpoints = System.getProperty("game.registry.integration.consul.endpoints", DEFAULT_ENDPOINTS);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String serviceName = "game-registry-name-it-" + suffix;
        ServiceInstance instance = ServiceInstance.builder()
                .name(serviceName)
                .id(serviceName + "-1")
                .address("127.0.0.1")
                .port(19002)
                .registrationTimeUTC(System.currentTimeMillis())
                .build();
        Properties properties = new Properties();
        properties.setProperty(ConsulRegistry.PROP_BLOCKING_QUERY_WAIT_SECONDS, "1");
        ConsulRegistry registry = new ConsulRegistry(endpoints, properties);

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
            try {
                registry.unregister(instance);
            } catch (RuntimeException ignored) {
            }
            registry.close();
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
