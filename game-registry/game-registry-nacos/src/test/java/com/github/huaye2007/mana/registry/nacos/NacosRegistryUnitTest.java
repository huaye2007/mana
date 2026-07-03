package com.github.huaye2007.mana.registry.nacos;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.github.huaye2007.mana.registry.api.DiscoveryEventType;
import com.github.huaye2007.mana.registry.api.ServiceInstance;
import com.github.huaye2007.mana.registry.api.ServiceInstanceListener;
import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosRegistryUnitTest {
    @Test
    void fromNacosInstancePreservesConfiguredGroupAndNamespace() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("group", "GAME_GROUP");
        properties.setProperty("namespace", "game-ns");
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848", properties);

        Instance nacosInstance = new Instance();
        nacosInstance.setIp("10.0.0.9");
        nacosInstance.setPort(9001);
        nacosInstance.setClusterName("arena");
        nacosInstance.setInstanceId("nacos-generated");
        nacosInstance.setMetadata(Map.of("id", "logic-1"));

        ServiceInstance instance = fromNacosInstance(registry, "logic", "GAME_GROUP", nacosInstance);

        assertEquals("logic-1", instance.getId());
        assertEquals("GAME_GROUP", instance.getMetadata().get(NacosRegistry.META_GROUP));
        assertEquals("game-ns", instance.getMetadata().get(NacosRegistry.META_NAMESPACE));
        assertEquals("arena", instance.getMetadata().get(NacosRegistry.META_CLUSTER));
        assertEquals(0L, instance.getRegistrationTimeUTC());
    }

    @Test
    void fromNacosInstanceUsesStableRegistrationTimeForExternalInstances() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        Instance nacosInstance = new Instance();
        nacosInstance.setIp("10.0.0.9");
        nacosInstance.setPort(9001);
        nacosInstance.setClusterName("arena");
        nacosInstance.setInstanceId("nacos-generated");
        nacosInstance.setMetadata(Map.of(NacosRegistry.META_REGISTRATION_TIME, "invalid"));

        ServiceInstance first = fromNacosInstance(registry, "logic", "DEFAULT_GROUP", nacosInstance);
        ServiceInstance second = fromNacosInstance(registry, "logic", "DEFAULT_GROUP", nacosInstance);

        assertEquals(0L, first.getRegistrationTimeUTC());
        assertEquals(first, second);
    }

    @Test
    void fromNacosInstanceUsesDefaultClusterWhenNacosClusterIsMissing() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        Instance nacosInstance = new Instance();
        nacosInstance.setIp("10.0.0.9");
        nacosInstance.setPort(9001);
        nacosInstance.setInstanceId("logic-1");

        ServiceInstance instance = fromNacosInstance(registry, "logic", "DEFAULT_GROUP", nacosInstance);

        assertEquals("DEFAULT", instance.getMetadata().get(NacosRegistry.META_CLUSTER));
    }

    @Test
    void fromNacosInstanceFiltersInvalidExternalMetadataEntries() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        Instance nacosInstance = new Instance();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("zone", "a");
        metadata.put("bad", null);
        metadata.put(null, "bad");
        nacosInstance.setIp("10.0.0.9");
        nacosInstance.setPort(9001);
        nacosInstance.setInstanceId("logic-1");
        nacosInstance.setMetadata(metadata);

        ServiceInstance instance = fromNacosInstance(registry, "logic", "DEFAULT_GROUP", nacosInstance);

        assertEquals("a", instance.getMetadata().get("zone"));
        assertFalse(instance.getMetadata().containsKey("bad"));
        assertFalse(instance.getMetadata().containsKey(null));
    }

    @Test
    void toNacosInstanceHandlesNullMetadata() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        ServiceInstance serviceInstance = new ServiceInstance();
        serviceInstance.setName("logic");
        serviceInstance.setAddress("10.0.0.9");
        serviceInstance.setPort(9001);
        serviceInstance.setMetadata(null);

        Instance nacosInstance = toNacosInstance(registry, serviceInstance);

        assertEquals("10.0.0.9", nacosInstance.getIp());
        assertEquals("10.0.0.9:9001", nacosInstance.getInstanceId());
        assertEquals("", nacosInstance.getMetadata().get("id"));
    }

    @Test
    void rejectsPerInstanceGroupThatDiffersFromRegistryConfiguration() {
        Properties properties = new Properties();
        properties.setProperty("group", "GAME_GROUP");
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848", properties);
        ServiceInstance serviceInstance = instance();
        serviceInstance.setMetadata(Map.of(NacosRegistry.META_GROUP, "OTHER_GROUP"));

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> toNacosInstance(registry, serviceInstance)
        );

        assertTrue(failure.getCause() instanceof RegistryOperationException);
    }

    @Test
    void registerUsesRegistryConfiguredGroup() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("group", "GAME_GROUP");
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848", properties);
        AtomicReference<String> capturedGroup = new AtomicReference<>();
        setField(registry, "namingService", namingService(capturedGroup));

        registry.register(instance());

        assertEquals("GAME_GROUP", capturedGroup.get());
    }

    @Test
    void unregisterUsesFullNacosInstanceIdentity() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("group", "GAME_GROUP");
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848", properties);
        AtomicReference<String> capturedGroup = new AtomicReference<>();
        AtomicReference<Instance> capturedInstance = new AtomicReference<>();
        setField(registry, "namingService", deregisterNamingService(capturedGroup, capturedInstance));
        ServiceInstance serviceInstance = instance();
        serviceInstance.setMetadata(Map.of(
                NacosRegistry.META_CLUSTER, "arena",
                NacosRegistry.META_EPHEMERAL, "false"
        ));

        registry.unregister(serviceInstance);

        assertEquals("GAME_GROUP", capturedGroup.get());
        Instance nacosInstance = capturedInstance.get();
        assertEquals("logic-1", nacosInstance.getInstanceId());
        assertEquals("10.0.0.9", nacosInstance.getIp());
        assertEquals(9001, nacosInstance.getPort());
        assertEquals("arena", nacosInstance.getClusterName());
        assertFalse(nacosInstance.isEphemeral());
    }

    @Test
    void registerRejectsMismatchedInstanceGroupBeforeCallingNacos() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("group", "GAME_GROUP");
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848", properties);
        AtomicReference<String> capturedGroup = new AtomicReference<>();
        setField(registry, "namingService", namingService(capturedGroup));
        ServiceInstance serviceInstance = instance();
        serviceInstance.setMetadata(Map.of(NacosRegistry.META_GROUP, "OTHER_GROUP"));

        assertThrows(RegistryOperationException.class, () -> registry.register(serviceInstance));
        assertEquals(null, capturedGroup.get());
    }

    @Test
    void getInstancesIgnoresNullNacosInstances() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        java.util.ArrayList<Instance> instances = new java.util.ArrayList<>();
        instances.add(null);
        instances.add(nacosInstance("logic-1", 9001));
        setField(registry, "namingService", queryNamingService(instances, List.of()));

        Collection<ServiceInstance> result = registry.getInstances("logic");

        assertEquals(List.of("logic-1"), result.stream().map(ServiceInstance::getId).toList());
    }

    @Test
    void getServiceNamesFiltersInvalidAndDuplicateNacosNames() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        names.add("room-service");
        names.add(" room-service ");
        names.add("room/service");
        names.add(null);
        names.add("alpha-service");
        names.add("room-service");
        setField(registry, "namingService", queryNamingService(List.of(), names));

        assertEquals(List.of("alpha-service", "room-service"), List.copyOf(registry.getServiceNames()));
    }

    @Test
    void blankInstanceIdUsesStableNacosInstanceIdAndPreservesApiFallbackKey() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        ServiceInstance serviceInstance = new ServiceInstance();
        serviceInstance.setName("logic");
        serviceInstance.setId(" ");
        serviceInstance.setAddress("10.0.0.9");
        serviceInstance.setPort(9001);

        Instance nacosInstance = toNacosInstance(registry, serviceInstance);
        ServiceInstance roundTrip = fromNacosInstance(registry, "logic", "DEFAULT_GROUP", nacosInstance);

        assertEquals("10.0.0.9:9001", nacosInstance.getInstanceId());
        assertTrue(roundTrip.getId().isBlank());
        assertEquals("10.0.0.9:9001", roundTrip.getKey());
    }

    @Test
    void namingEventHandlerIgnoresMalformedEventsWithoutEscapingCallbackThread() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        ConcurrentMap<String, ServiceInstance> known = new ConcurrentHashMap<>();
        ServiceInstanceListener failingListener = event -> {
            throw new IllegalStateException("listener failed");
        };

        assertDoesNotThrow(() -> handleNamingEvent(
                registry,
                "logic",
                failingListener,
                known,
                new NamingEvent("logic", Collections.singletonList(null))
        ));

        assertTrue(known.isEmpty());
    }

    @Test
    void namingEventHandlerIgnoresUnexpectedServiceName() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        ConcurrentMap<String, ServiceInstance> known = new ConcurrentHashMap<>();
        AtomicInteger events = new AtomicInteger();

        handleNamingEvent(
                registry,
                "logic",
                event -> events.incrementAndGet(),
                known,
                new NamingEvent("match", List.of(nacosInstance("match-1", 9001)))
        );

        assertEquals(0, events.get());
        assertTrue(known.isEmpty());
    }

    @Test
    void namingEventHandlerAcceptsDefaultGroupQualifiedServiceName() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        ConcurrentMap<String, ServiceInstance> known = new ConcurrentHashMap<>();
        AtomicInteger events = new AtomicInteger();

        handleNamingEvent(
                registry,
                "logic",
                event -> events.incrementAndGet(),
                known,
                new NamingEvent("DEFAULT_GROUP@@logic", List.of(nacosInstance("logic-1", 9001)))
        );

        assertEquals(1, events.get());
        assertTrue(known.containsKey("logic-1"));
    }

    @Test
    void watchServiceSubscribesBeforeLoadingInitialSnapshot() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger subscribeOrder = new AtomicInteger();
        AtomicInteger queryOrder = new AtomicInteger();
        setField(registry, "namingService", orderingNamingService(sequence, subscribeOrder, queryOrder));

        AutoCloseable handle = registry.watchService("logic", event -> {
        });
        try {
            assertTrue(subscribeOrder.get() > 0);
            assertTrue(queryOrder.get() > 0);
            assertTrue(subscribeOrder.get() < queryOrder.get());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void watchServiceDoesNotDuplicateSynchronousSubscribeSnapshot() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        AtomicInteger events = new AtomicInteger();
        Instance nacosInstance = nacosInstance("logic-1", 9001);
        setField(registry, "namingService", synchronousSnapshotNamingService(nacosInstance));

        AutoCloseable handle = registry.watchService("logic", event -> events.incrementAndGet());
        try {
            assertEquals(1, events.get());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void serviceNameWatchIntervalIsConfigurable() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(NacosRegistry.PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS, "1000");

        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848", properties);

        assertEquals(1000L, serviceNameWatchIntervalMillis(registry));
        assertEquals(1000L, registry.getServiceNameWatchIntervalMillis());
    }

    @Test
    void serviceNameWatchPollsThroughCancelableScheduler() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(NacosRegistry.PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS, "20");
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848", properties);
        CopyOnWriteArrayList<String> names = new CopyOnWriteArrayList<>(List.of("logic"));
        setField(registry, "namingService", dynamicServiceNameNamingService(names));
        AtomicInteger matchAdded = new AtomicInteger();
        AtomicInteger arenaAddedAfterClose = new AtomicInteger();

        AutoCloseable handle = registry.watchServiceNames(event -> {
            if (event.getType() == DiscoveryEventType.ADDED && "match".equals(event.getServiceName())) {
                matchAdded.incrementAndGet();
            }
            if (event.getType() == DiscoveryEventType.ADDED && "arena".equals(event.getServiceName())) {
                arenaAddedAfterClose.incrementAndGet();
            }
        });
        try {
            names.add("match");
            long deadline = System.currentTimeMillis() + 1000L;
            while (matchAdded.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(1, matchAdded.get());

            handle.close();
            names.add("arena");
            Thread.sleep(80L);
            assertEquals(0, arenaAddedAfterClose.get());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void serviceNameWatchThreadFactoryCreatesNamedDaemonThreads() throws Exception {
        ThreadFactory factory = daemonThreadFactory();

        Thread thread = factory.newThread(() -> {
        });

        assertTrue(thread.isDaemon());
        assertTrue(thread.getName().startsWith("nacos-service-name-watcher-"));
    }

    @Test
    void serverAddressIsNormalized() throws Exception {
        NacosRegistry registry = new NacosRegistry(" 127.0.0.1:8848, 127.0.0.2:8848 ");

        assertEquals("127.0.0.1:8848,127.0.0.2:8848", serverAddr(registry));
    }

    @Test
    void rejectsInvalidServiceNameWatchInterval() {
        Properties properties = new Properties();
        properties.setProperty(NacosRegistry.PROP_SERVICE_NAME_WATCH_INTERVAL_MILLIS, "0");

        assertThrows(RegistryOperationException.class, () -> new NacosRegistry("127.0.0.1:8848", properties));
    }

    @Test
    void rejectsInvalidRawProperties() {
        Properties blankKey = new Properties();
        blankKey.setProperty(" ", "value");
        assertThrows(RegistryOperationException.class, () -> new NacosRegistry("127.0.0.1:8848", blankKey));

        Properties nonStringValue = new Properties();
        nonStringValue.put("timeout", 1000);
        assertThrows(RegistryOperationException.class, () -> new NacosRegistry("127.0.0.1:8848", nonStringValue));
    }

    @Test
    void closeIsIdempotentBeforeStart() {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");

        registry.close();
        registry.close();
    }

    @Test
    void rejectsStartAfterClose() {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        registry.close();

        assertThrows(RegistryOperationException.class, registry::start);
    }

    @Test
    void rejectsOperationsAfterCloseWithClosedMessage() {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        registry.close();

        RegistryOperationException failure = assertThrows(
                RegistryOperationException.class,
                () -> registry.register(instance())
        );

        assertEquals("Nacos registry has been closed", failure.getMessage());
    }

    @Test
    void closeReportsWatchFailuresAndClearsHandles() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        ConcurrentMap<Long, AutoCloseable> handles = watchHandles(registry);
        handles.put(1L, () -> {
            throw new IllegalStateException("watch close failed");
        });

        RegistryOperationException failure = assertThrows(RegistryOperationException.class, registry::close);

        assertEquals("Failed to close nacos watch", failure.getMessage());
        assertTrue(handles.isEmpty());
        registry.close();
    }

    @Test
    void closesLateWatchHandleRegisteredAfterClose() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        AtomicInteger closes = new AtomicInteger();
        registry.close();

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> registerWatchHandle(registry, closes::incrementAndGet));

        assertTrue(failure.getCause() instanceof RegistryOperationException);
        assertEquals("Nacos registry has been closed", failure.getCause().getMessage());
        assertEquals(1, closes.get());
        assertTrue(watchHandles(registry).isEmpty());
    }

    @Test
    void exposesRegistryAndWatchState() throws Exception {
        NacosRegistry registry = new NacosRegistry("127.0.0.1:8848");
        setField(registry, "namingService", queryNamingService(List.of(nacosInstance("logic-1", 9001)), List.of()));

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

    private ServiceInstance fromNacosInstance(
            NacosRegistry registry,
            String serviceName,
            String group,
            Instance instance
    ) throws Exception {
        Method method = NacosRegistry.class.getDeclaredMethod(
                "fromNacosInstance",
                String.class,
                String.class,
                Instance.class
        );
        method.setAccessible(true);
        return (ServiceInstance) method.invoke(registry, serviceName, group, instance);
    }

    private Instance toNacosInstance(NacosRegistry registry, ServiceInstance serviceInstance) throws Exception {
        Method method = NacosRegistry.class.getDeclaredMethod("toNacosInstance", ServiceInstance.class);
        method.setAccessible(true);
        return (Instance) method.invoke(registry, serviceInstance);
    }

    private void handleNamingEvent(
            NacosRegistry registry,
            String serviceName,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> known,
            NamingEvent event
    ) throws Exception {
        Method method = NacosRegistry.class.getDeclaredMethod(
                "handleNamingEvent",
                String.class,
                ServiceInstanceListener.class,
                ConcurrentMap.class,
                NamingEvent.class
        );
        method.setAccessible(true);
        method.invoke(registry, serviceName, listener, known, event);
    }

    private AutoCloseable registerWatchHandle(NacosRegistry registry, AutoCloseable handle) throws Exception {
        Method method = NacosRegistry.class.getDeclaredMethod("registerWatchHandle", AutoCloseable.class);
        method.setAccessible(true);
        return (AutoCloseable) method.invoke(registry, handle);
    }

    private ServiceInstance instance() {
        ServiceInstance instance = new ServiceInstance();
        instance.setName("logic");
        instance.setId("logic-1");
        instance.setAddress("10.0.0.9");
        instance.setPort(9001);
        return instance;
    }

    private long serviceNameWatchIntervalMillis(NacosRegistry registry) throws Exception {
        Field field = NacosRegistry.class.getDeclaredField("serviceNameWatchIntervalMillis");
        field.setAccessible(true);
        return (long) field.get(registry);
    }

    private String serverAddr(NacosRegistry registry) throws Exception {
        Field field = NacosRegistry.class.getDeclaredField("serverAddr");
        field.setAccessible(true);
        return (String) field.get(registry);
    }

    private ThreadFactory daemonThreadFactory() throws Exception {
        Method method = NacosRegistry.class.getDeclaredMethod("daemonThreadFactory");
        method.setAccessible(true);
        return (ThreadFactory) method.invoke(null);
    }

    private void setField(NacosRegistry registry, String name, Object value) throws Exception {
        Field field = NacosRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private NamingService namingService(AtomicReference<String> capturedGroup) {
        return (NamingService) java.lang.reflect.Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("registerInstance".equals(method.getName())
                            && args != null
                            && args.length == 3
                            && args[2] instanceof Instance) {
                        capturedGroup.set((String) args[1]);
                        return null;
                    }
                    if ("shutDown".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private NamingService deregisterNamingService(
            AtomicReference<String> capturedGroup,
            AtomicReference<Instance> capturedInstance) {
        return (NamingService) java.lang.reflect.Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("deregisterInstance".equals(method.getName())
                            && args != null
                            && args.length == 3
                            && args[2] instanceof Instance instance) {
                        capturedGroup.set((String) args[1]);
                        capturedInstance.set(instance);
                        return null;
                    }
                    if ("shutDown".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private NamingService orderingNamingService(
            AtomicInteger sequence,
            AtomicInteger subscribeOrder,
            AtomicInteger queryOrder
    ) {
        return (NamingService) java.lang.reflect.Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("subscribe".equals(method.getName()) && args != null && args.length == 3) {
                        subscribeOrder.compareAndSet(0, sequence.incrementAndGet());
                        return null;
                    }
                    if ("getAllInstances".equals(method.getName())) {
                        queryOrder.compareAndSet(0, sequence.incrementAndGet());
                        return List.of(nacosInstance("logic-1", 9001));
                    }
                    if ("unsubscribe".equals(method.getName()) || "shutDown".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private NamingService synchronousSnapshotNamingService(Instance instance) {
        return (NamingService) java.lang.reflect.Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("subscribe".equals(method.getName()) && args != null && args.length == 3) {
                        ((EventListener) args[2]).onEvent(new NamingEvent("logic", List.of(instance)));
                        return null;
                    }
                    if ("getAllInstances".equals(method.getName())) {
                        return List.of(instance);
                    }
                    if ("unsubscribe".equals(method.getName()) || "shutDown".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private NamingService queryNamingService(List<Instance> instances, List<String> serviceNames) {
        return (NamingService) java.lang.reflect.Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("getAllInstances".equals(method.getName())) {
                        return instances;
                    }
                    if ("getServicesOfServer".equals(method.getName())) {
                        ListView<String> view = new ListView<>();
                        view.setData(serviceNames);
                        return view;
                    }
                    if ("shutDown".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private NamingService dynamicServiceNameNamingService(List<String> serviceNames) {
        return (NamingService) java.lang.reflect.Proxy.newProxyInstance(
                NamingService.class.getClassLoader(),
                new Class<?>[]{NamingService.class},
                (proxy, method, args) -> {
                    if ("getServicesOfServer".equals(method.getName())) {
                        ListView<String> view = new ListView<>();
                        view.setData(List.copyOf(serviceNames));
                        return view;
                    }
                    if ("shutDown".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Instance nacosInstance(String id, int port) {
        Instance instance = new Instance();
        instance.setIp("10.0.0.9");
        instance.setPort(port);
        instance.setInstanceId(id);
        instance.setMetadata(Map.of("id", id, NacosRegistry.META_REGISTRATION_TIME, "100"));
        return instance;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Long, AutoCloseable> watchHandles(NacosRegistry registry) throws Exception {
        Field field = NacosRegistry.class.getDeclaredField("watchHandles");
        field.setAccessible(true);
        return (ConcurrentMap<Long, AutoCloseable>) field.get(registry);
    }
}
