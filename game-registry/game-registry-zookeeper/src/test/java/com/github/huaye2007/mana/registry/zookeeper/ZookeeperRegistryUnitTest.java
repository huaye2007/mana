package com.github.huaye2007.mana.registry.zookeeper;

import com.github.huaye2007.mana.registry.api.DiscoveryEventType;
import com.github.huaye2007.mana.registry.api.ServiceInstance;
import com.github.huaye2007.mana.registry.api.ServiceInstanceListener;
import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZookeeperRegistryUnitTest {
    @Test
    void blankInstanceIdUsesStableFallbackPathIdAndPreservesApiId() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        try {
            ServiceInstance instance = new ServiceInstance();
            instance.setName("fallback-server");
            instance.setAddress("10.0.0.8");
            instance.setPort(9000);

            org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload> curatorInstance =
                    toCurator(registry, instance);
            ServiceInstance roundTrip = fromCurator(registry, curatorInstance);

            assertEquals("10.0.0.8:9000", curatorInstance.getId());
            assertEquals("10.0.0.8:9000", roundTrip.getKey());
            assertTrue(roundTrip.getId() == null || roundTrip.getId().isBlank());
        } finally {
            registry.close();
        }
    }

    @Test
    void explicitInstanceIdIsUsedForBothPathAndApiId() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        try {
            ServiceInstance instance = new ServiceInstance();
            instance.setName("logic-server");
            instance.setId("logic-1");
            instance.setAddress("10.0.0.9");
            instance.setPort(9001);

            org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload> curatorInstance =
                    toCurator(registry, instance);
            ServiceInstance roundTrip = fromCurator(registry, curatorInstance);

            assertEquals("logic-1", curatorInstance.getId());
            assertEquals("logic-1", roundTrip.getId());
            assertEquals("logic-1", roundTrip.getKey());
        } finally {
            registry.close();
        }
    }

    @Test
    void oldPayloadWithoutOriginalIdKeepsCuratorInstanceId() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        try {
            org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload> curatorInstance =
                    org.apache.curator.x.discovery.ServiceInstance
                            .<ZookeeperRegistry.ServiceInstancePayload>builder()
                            .name("legacy-server")
                            .id("legacy-1")
                            .address("10.0.0.10")
                            .port(9002)
                            .payload(new ZookeeperRegistry.ServiceInstancePayload(2.0D, true, null))
                            .build();

            ServiceInstance roundTrip = fromCurator(registry, curatorInstance);

            assertEquals("legacy-1", roundTrip.getId());
            assertEquals("legacy-1", roundTrip.getKey());
        } finally {
            registry.close();
        }
    }

    @Test
    void fromCuratorFiltersInvalidPayloadMetadataEntries() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("zone", "a");
            metadata.put("bad", null);
            metadata.put(null, "bad");
            org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload> curatorInstance =
                    org.apache.curator.x.discovery.ServiceInstance
                            .<ZookeeperRegistry.ServiceInstancePayload>builder()
                            .name("logic-server")
                            .id("logic-1")
                            .address("10.0.0.10")
                            .port(9002)
                            .payload(new ZookeeperRegistry.ServiceInstancePayload("logic-1", 2.0D, true, metadata))
                            .build();

            ServiceInstance roundTrip = fromCurator(registry, curatorInstance);

            assertEquals("a", roundTrip.getMetadata().get("zone"));
            assertTrue(!roundTrip.getMetadata().containsKey("bad"));
            assertTrue(!roundTrip.getMetadata().containsKey(null));
        } finally {
            registry.close();
        }
    }

    @Test
    void getInstancesIgnoresNullCuratorInstances() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        ArrayList<org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload>> instances =
                new ArrayList<>();
        instances.add(null);
        instances.add(curatorInstance("logic-server", "logic-1", 9001));
        setField(registry, "started", true);
        setField(registry, "serviceDiscovery", queryServiceDiscovery(instances, List.of()));

        try {
            Collection<ServiceInstance> result = registry.getInstances("logic-server");

            assertEquals(List.of("logic-1"), result.stream().map(ServiceInstance::getId).toList());
        } finally {
            registry.close();
        }
    }

    @Test
    void getInstancesUsesRequestedServiceNameWhenCuratorNameDiffers() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        setField(registry, "started", true);
        setField(registry, "serviceDiscovery", queryServiceDiscovery(
                List.of(curatorInstance("match-service", "room-1", 9001)),
                List.of()
        ));

        try {
            ServiceInstance result = registry.getInstances("room-service").iterator().next();

            assertEquals("room-service", result.getName());
        } finally {
            registry.close();
        }
    }

    @Test
    void getServiceNamesFiltersInvalidAndDuplicateCuratorNames() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        ArrayList<String> names = new ArrayList<>();
        names.add("room-service");
        names.add(" room-service ");
        names.add("room/service");
        names.add(null);
        names.add("alpha-service");
        names.add("room-service");
        setField(registry, "started", true);
        setField(registry, "serviceDiscovery", queryServiceDiscovery(List.of(), names));

        try {
            assertEquals(List.of("alpha-service", "room-service"), List.copyOf(registry.getServiceNames()));
        } finally {
            registry.close();
        }
    }

    @Test
    void closesLateWatchHandleRegisteredAfterClose() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        AtomicInteger closes = new AtomicInteger();
        registry.close();

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> registerWatchHandle(registry, closes::incrementAndGet));

        assertTrue(failure.getCause() instanceof RegistryOperationException);
        assertEquals("Zookeeper registry has been closed", failure.getCause().getMessage());
        assertEquals(1, closes.get());
    }

    @Test
    void closesServiceWatchCacheWhenInitialSnapshotFails() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ZookeeperRegistry registry = closeCountingCacheRegistry(closes);
        setField(registry, "started", true);
        setField(registry, "serviceDiscovery", throwingQueryServiceDiscovery());

        try {
            assertThrows(RegistryOperationException.class,
                    () -> registry.watchService("logic-server", event -> {
                    }));

            assertEquals(1, closes.get());
        } finally {
            registry.close();
        }
    }

    @Test
    void closesServiceNameWatchCacheWhenInitialSnapshotFails() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ZookeeperRegistry registry = closeCountingCacheRegistry(closes);
        setField(registry, "started", true);
        setField(registry, "serviceDiscovery", throwingQueryServiceDiscovery());

        try {
            assertThrows(RegistryOperationException.class,
                    () -> registry.watchServiceNames(event -> {
                    }));

            assertEquals(1, closes.get());
        } finally {
            registry.close();
        }
    }

    @Test
    void exposesRegistryAndWatchState() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        setField(registry, "started", true);

        AutoCloseable handle = registerWatchHandle(registry, () -> {
        });

        assertTrue(registry.isStarted());
        assertTrue(!registry.isClosed());
        assertEquals(1, registry.getActiveWatchCount());

        handle.close();
        assertEquals(0, registry.getActiveWatchCount());
        registry.close();
        assertTrue(registry.isClosed());
        assertTrue(!registry.isStarted());
    }

    @Test
    void watchedInstanceEventsIgnoreUnchangedDuplicateUpdates() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        ConcurrentMap<String, ServiceInstance> previous = new ConcurrentHashMap<>();
        AtomicInteger events = new AtomicInteger();
        AtomicReference<DiscoveryEventType> lastType = new AtomicReference<>();
        ServiceInstance first = instance();
        ServiceInstance changed = first.copy();
        changed.setWeight(2.0D);
        ServiceInstanceListener listener = event -> {
            events.incrementAndGet();
            lastType.set(event.getType());
        };

        try {
            handleWatchedInstanceEvent(
                    registry,
                    listener,
                    previous,
                    CuratorCacheListener.Type.NODE_CREATED,
                    first
            );
            handleWatchedInstanceEvent(
                    registry,
                    listener,
                    previous,
                    CuratorCacheListener.Type.NODE_CHANGED,
                    first.copy()
            );
            handleWatchedInstanceEvent(
                    registry,
                    listener,
                    previous,
                    CuratorCacheListener.Type.NODE_CHANGED,
                    changed
            );

            assertEquals(2, events.get());
            assertEquals(DiscoveryEventType.UPDATED, lastType.get());
        } finally {
            registry.close();
        }
    }

    @Test
    void watchedInstanceEventsIgnoreDuplicateRemovals() throws Exception {
        ZookeeperRegistry registry = new ZookeeperRegistry("127.0.0.1:2181", "/services");
        ConcurrentMap<String, ServiceInstance> previous = new ConcurrentHashMap<>();
        AtomicInteger events = new AtomicInteger();
        ServiceInstance first = instance();
        ServiceInstanceListener listener = event -> events.incrementAndGet();

        try {
            handleWatchedInstanceEvent(
                    registry,
                    listener,
                    previous,
                    CuratorCacheListener.Type.NODE_CREATED,
                    first
            );
            handleWatchedInstanceEvent(
                    registry,
                    listener,
                    previous,
                    CuratorCacheListener.Type.NODE_DELETED,
                    first.copy()
            );
            handleWatchedInstanceEvent(
                    registry,
                    listener,
                    previous,
                    CuratorCacheListener.Type.NODE_DELETED,
                    first.copy()
            );

            assertEquals(2, events.get());
        } finally {
            registry.close();
        }
    }

    @SuppressWarnings("unchecked")
    private org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload> toCurator(
            ZookeeperRegistry registry,
            ServiceInstance instance
    ) throws Exception {
        Method method = ZookeeperRegistry.class.getDeclaredMethod("toCurator", ServiceInstance.class);
        method.setAccessible(true);
        return (org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload>)
                method.invoke(registry, instance);
    }

    private ServiceInstance fromCurator(
            ZookeeperRegistry registry,
            org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload> instance
    ) throws Exception {
        Method method = ZookeeperRegistry.class.getDeclaredMethod(
                "fromCurator",
                org.apache.curator.x.discovery.ServiceInstance.class
        );
        method.setAccessible(true);
        return (ServiceInstance) method.invoke(registry, instance);
    }

    private void handleWatchedInstanceEvent(
            ZookeeperRegistry registry,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> previous,
            CuratorCacheListener.Type eventType,
            ServiceInstance instance
    ) throws Exception {
        Method method = ZookeeperRegistry.class.getDeclaredMethod(
                "handleWatchedInstanceEvent",
                String.class,
                ServiceInstanceListener.class,
                ConcurrentMap.class,
                CuratorCacheListener.Type.class,
                ServiceInstance.class
        );
        method.setAccessible(true);
        method.invoke(registry, "room-service", listener, previous, eventType, instance);
    }

    private AutoCloseable registerWatchHandle(ZookeeperRegistry registry, AutoCloseable handle) throws Exception {
        Method method = ZookeeperRegistry.class.getDeclaredMethod("registerWatchHandle", AutoCloseable.class);
        method.setAccessible(true);
        return (AutoCloseable) method.invoke(registry, handle);
    }

    private ZookeeperRegistry closeCountingCacheRegistry(AtomicInteger closes) {
        return new ZookeeperRegistry("127.0.0.1:2181", "/services") {
            @Override
            CuratorCache newCuratorCache(String path) {
                return closeCountingCuratorCache(closes);
            }
        };
    }

    private CuratorCache closeCountingCuratorCache(AtomicInteger closes) {
        return (CuratorCache) Proxy.newProxyInstance(
                CuratorCache.class.getClassLoader(),
                new Class<?>[]{CuratorCache.class},
                (proxy, method, args) -> {
                    if ("listenable".equals(method.getName())) {
                        Class<?> listenableType = method.getReturnType();
                        return Proxy.newProxyInstance(
                                listenableType.getClassLoader(),
                                new Class<?>[]{listenableType},
                                (listenableProxy, listenableMethod, listenableArgs) -> null
                        );
                    }
                    if ("start".equals(method.getName())) {
                        return null;
                    }
                    if ("close".equals(method.getName())) {
                        closes.incrementAndGet();
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "closeCountingCuratorCache";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                }
        );
    }

    private void setField(ZookeeperRegistry registry, String name, Object value) throws Exception {
        Field field = ZookeeperRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    @SuppressWarnings("unchecked")
    private ServiceDiscovery<ZookeeperRegistry.ServiceInstancePayload> queryServiceDiscovery(
            Collection<org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload>> instances,
            Collection<String> serviceNames
    ) {
        return (ServiceDiscovery<ZookeeperRegistry.ServiceInstancePayload>) java.lang.reflect.Proxy.newProxyInstance(
                ServiceDiscovery.class.getClassLoader(),
                new Class<?>[]{ServiceDiscovery.class},
                (proxy, method, args) -> {
                    if ("queryForInstances".equals(method.getName())) {
                        return instances;
                    }
                    if ("queryForNames".equals(method.getName())) {
                        return serviceNames;
                    }
                    if ("close".equals(method.getName()) || "start".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    @SuppressWarnings("unchecked")
    private ServiceDiscovery<ZookeeperRegistry.ServiceInstancePayload> throwingQueryServiceDiscovery() {
        return (ServiceDiscovery<ZookeeperRegistry.ServiceInstancePayload>) Proxy.newProxyInstance(
                ServiceDiscovery.class.getClassLoader(),
                new Class<?>[]{ServiceDiscovery.class},
                (proxy, method, args) -> {
                    if ("queryForInstances".equals(method.getName()) || "queryForNames".equals(method.getName())) {
                        throw new IllegalStateException("query failed");
                    }
                    if ("close".equals(method.getName()) || "start".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private org.apache.curator.x.discovery.ServiceInstance<ZookeeperRegistry.ServiceInstancePayload> curatorInstance(
            String serviceName,
            String id,
            int port
    ) throws Exception {
        return org.apache.curator.x.discovery.ServiceInstance
                .<ZookeeperRegistry.ServiceInstancePayload>builder()
                .name(serviceName)
                .id(id)
                .address("10.0.0.10")
                .port(port)
                .payload(new ZookeeperRegistry.ServiceInstancePayload(id, 1.0D, true, null))
                .build();
    }

    private ServiceInstance instance() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("room-service");
        instance.setId("room-1");
        instance.setAddress("10.0.0.8");
        instance.setPort(9000);
        instance.setRegistrationTimeUTC(100L);
        return instance;
    }
}
