package com.github.huaye2007.mana.registry.etcd;

import com.github.huaye2007.mana.registry.api.ServiceInstance;
import com.github.huaye2007.mana.registry.exception.RegistryOperationException;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.api.RangeResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.watch.WatchEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EtcdRegistryUnitTest {
    @Test
    void buildKeyFallsBackToAddressAndPortWhenIdIsBlank() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        try {
            ServiceInstance instance = new ServiceInstance();
            instance.setName("room-service");
            instance.setAddress("10.0.0.8");
            instance.setPort(9000);

            assertEquals("/services/room-service/10.0.0.8:9000", buildKey(registry, instance));
        } finally {
            registry.close();
        }
    }

    @Test
    void buildKeyPrefersExplicitInstanceId() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        try {
            ServiceInstance instance = new ServiceInstance();
            instance.setName("room-service");
            instance.setId("room-1");
            instance.setAddress("10.0.0.8");
            instance.setPort(9000);

            assertEquals("/services/room-service/room-1", buildKey(registry, instance));
        } finally {
            registry.close();
        }
    }

    @Test
    void deserializePreservesBlankInstanceIdAndFallbackKey() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        try {
            ServiceInstance instance = deserializeInstance(
                    registry,
                    "/services/room-service/10.0.0.8:9000",
                    "{\"name\":\"room-service\",\"id\":\"\",\"address\":\"10.0.0.8\",\"port\":9000}"
            );

            assertTrue(instance.getId().isBlank());
            assertEquals("10.0.0.8:9000", instance.getKey());
        } finally {
            registry.close();
        }
    }

    @Test
    void deserializeUsesPathIdForLegacyPayloadWithoutIdField() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        try {
            ServiceInstance instance = deserializeInstance(
                    registry,
                    "/services/room-service/room-1",
                    "{\"name\":\"room-service\",\"address\":\"10.0.0.8\",\"port\":9000}"
            );

            assertEquals("room-1", instance.getId());
            assertEquals("room-1", instance.getKey());
        } finally {
            registry.close();
        }
    }

    @Test
    void registrationKeyIncludesServiceNameToAvoidCrossServiceCollisions() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        try {
            ServiceInstance first = instance("room-service", "shared-id", 9000);
            ServiceInstance second = instance("match-service", "shared-id", 9001);

            assertEquals("/services/room-service/shared-id", registrationKey(registry, first));
            assertEquals("/services/match-service/shared-id", registrationKey(registry, second));
        } finally {
            registry.close();
        }
    }

    @Test
    void failedRemoteRegisterDoesNotLeaveInstanceTrackedForLeaseReconnect() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setLeaseId(registry, 1L);
        setField(registry, "kvClient", failingKvClient());

        try {
            assertThrows(RegistryOperationException.class, () -> registry.register(instance()));
            assertTrue(registeredInstances(registry).isEmpty());
        } finally {
            setLeaseId(registry, 0L);
            registry.close();
        }
    }

    @Test
    void remoteRegisterUsesConfiguredOperationTimeout() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(EtcdRegistry.PROP_OPERATION_TIMEOUT_MILLIS, "10");
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10, properties);
        setField(registry, "started", true);
        setLeaseId(registry, 1L);
        setField(registry, "kvClient", hangingPutKvClient());

        try {
            RegistryOperationException failure = assertThrows(
                    RegistryOperationException.class,
                    () -> registry.register(instance()));

            assertEquals("Failed to register service in etcd: room-service", failure.getMessage());
            assertTrue(failure.getCause() instanceof RegistryOperationException);
            assertTrue(failure.getCause().getMessage().contains("timed out after 10ms"));
            assertTrue(registeredInstances(registry).isEmpty());
        } finally {
            setLeaseId(registry, 0L);
            registry.close();
        }
    }

    @Test
    void startDoesNotExposeRegistryBeforeLeaseGrantCompletes() throws Exception {
        CountDownLatch grantCalled = new CountDownLatch(1);
        CompletableFuture<LeaseGrantResponse> grantFuture = new CompletableFuture<>();
        AtomicInteger putCalls = new AtomicInteger();
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "leaseClient", controllableLeaseClient(grantCalled, grantFuture));
        setField(registry, "kvClient", countingPutKvClient(putCalls));

        Thread starter = new Thread(() -> {
            try {
                registry.start();
            } catch (Throwable t) {
                startFailure.set(t);
            }
        });
        starter.start();

        try {
            assertTrue(grantCalled.await(2, TimeUnit.SECONDS));
            RegistryOperationException failure = assertThrows(
                    RegistryOperationException.class,
                    () -> registry.register(instance())
            );
            assertEquals("Etcd registry has not been started", failure.getMessage());
            assertEquals(0, putCalls.get());

            grantFuture.complete(new LeaseGrantResponse(
                    io.etcd.jetcd.api.LeaseGrantResponse.newBuilder()
                            .setID(7L)
                            .setTTL(10L)
                            .build()
            ));
            starter.join(2_000L);

            assertEquals(null, startFailure.get());
        } finally {
            grantFuture.completeExceptionally(new IllegalStateException("test cleanup"));
            starter.join(2_000L);
            registry.close();
        }
    }

    @Test
    void queryFillsMissingServiceNameAndIdFromEtcdKey() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", responseKvClient(
                apiKeyValue("/services/room-service/room-1", "{\"address\":\"10.0.0.8\",\"port\":9000}")
        ));

        try {
            ServiceInstance instance = registry.getInstances("room-service").iterator().next();

            assertEquals("room-service", instance.getName());
            assertEquals("room-1", instance.getId());
        } finally {
            registry.close();
        }
    }

    @Test
    void queryUsesEtcdKeyServiceNameWhenPayloadNameDiffers() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", responseKvClient(
                apiKeyValue(
                        "/services/room-service/room-1",
                        "{\"name\":\"match-service\",\"id\":\"room-1\",\"address\":\"10.0.0.8\",\"port\":9000}"
                )
        ));

        try {
            ServiceInstance instance = registry.getInstances("room-service").iterator().next();

            assertEquals("room-service", instance.getName());
        } finally {
            registry.close();
        }
    }

    @Test
    void queryFiltersInvalidEtcdMetadataEntries() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", responseKvClient(
                apiKeyValue(
                        "/services/room-service/room-1",
                        "{\"address\":\"10.0.0.8\",\"port\":9000,\"metadata\":{\"zone\":\"a\",\"bad\":null}}"
                )
        ));

        try {
            ServiceInstance instance = registry.getInstances("room-service").iterator().next();

            assertEquals("a", instance.getMetadata().get("zone"));
            assertTrue(!instance.getMetadata().containsKey("bad"));
        } finally {
            registry.close();
        }
    }

    @Test
    void queryToleratesUnknownJsonFields() throws Exception {
        // 跨版本部署:新节点写入了本节点不识别的字段,getInstances 不能抛 RegistrySerializationException。
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", responseKvClient(
                apiKeyValue(
                        "/services/room-service/room-1",
                        "{\"address\":\"10.0.0.8\",\"port\":9000,\"futureField\":\"future-value\",\"futureNested\":{\"x\":1}}"
                )
        ));

        try {
            ServiceInstance instance = registry.getInstances("room-service").iterator().next();

            assertEquals("room-service", instance.getName());
            assertEquals("10.0.0.8", instance.getAddress());
            assertEquals(9000, instance.getPort());
        } finally {
            registry.close();
        }
    }

    @Test
    void serviceNameQueryIgnoresMalformedEtcdKeys() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", responseKvClient(
                apiKeyValue("/services/room-service/room-1", "{}"),
                apiKeyValue("/services", "{}")
        ));

        try {
            assertEquals(List.of("room-service"), List.copyOf(registry.getServiceNames()));
        } finally {
            registry.close();
        }
    }

    @Test
    void serviceNameQueryFiltersInvalidAndDuplicateEtcdNames() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", responseKvClient(
                apiKeyValue("/services/room-service/room-1", "{}"),
                apiKeyValue("/services/ room-service /room-2", "{}"),
                apiKeyValue("/services/room-service/room-3", "{}"),
                apiKeyValue("/services/alpha-service/alpha-1", "{}")
        ));

        try {
            assertEquals(List.of("alpha-service", "room-service"), List.copyOf(registry.getServiceNames()));
        } finally {
            registry.close();
        }
    }

    @Test
    void schedulesOnlyOneLeaseReconnectAtATime() throws Exception {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "currentRetryDelayMs", 60_000L);

        try {
            scheduleReconnection(registry);
            scheduleReconnection(registry);

            assertTrue(reconnectScheduled(registry).get());
            assertEquals(30_000L, currentRetryDelayMs(registry));
        } finally {
            registry.close();
        }
    }

    @Test
    void exposesRegistryAndWatchState() throws Exception {
        AtomicReference<Watch.Listener> listener = new AtomicReference<>();
        AtomicInteger watchCount = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setLeaseId(registry, 7L);
        setField(registry, "kvClient", emptyKvClient());
        setField(registry, "watchClient", recordingWatchClient(listener, watchCount));
        registeredInstances(registry).put("/services/room-service/room-1", instance());

        AutoCloseable handle = registry.watchService("room-service", event -> {
        });
        try {
            assertTrue(registry.isStarted());
            assertTrue(!registry.isClosed());
            assertEquals(7L, registry.getCurrentLeaseId());
            assertEquals(1, registry.getRegisteredInstanceCount());
            assertEquals(1, registry.getActiveWatchCount());
        } finally {
            handle.close();
            registry.close();
        }

        assertTrue(registry.isClosed());
        assertTrue(!registry.isStarted());
        assertEquals(0, registry.getRegisteredInstanceCount());
        assertEquals(0, registry.getActiveWatchCount());
    }

    @Test
    void retryThreadFactoryCreatesNamedDaemonThreads() throws Exception {
        ThreadFactory factory = daemonThreadFactory();

        Thread thread = factory.newThread(() -> {
        });

        assertTrue(thread.isDaemon());
        assertTrue(thread.getName().startsWith("game-registry-etcd-retry-"));
    }

    @Test
    void reconnectsServiceWatchAfterEtcdWatchCompletes() throws Exception {
        AtomicReference<Watch.Listener> listener = new AtomicReference<>();
        AtomicInteger watchCount = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", emptyKvClient());
        setField(registry, "watchClient", recordingWatchClient(listener, watchCount));

        AutoCloseable handle = registry.watchService("room-service", event -> {
        });
        try {
            assertEquals(1, watchCount.get());

            listener.get().onCompleted();
            assertEquals(2000L, watchRetryDelayMs(watchRegistrations(registry).values().iterator().next()));

            long deadline = System.currentTimeMillis() + 3_000L;
            while (watchCount.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertEquals(2, watchCount.get());
            assertEquals(1000L, watchRetryDelayMs(watchRegistrations(registry).values().iterator().next()));
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void opensServiceWatchBeforeLoadingInitialSnapshot() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger watchOrder = new AtomicInteger();
        AtomicInteger getOrder = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", orderedEmptyKvClient(sequence, getOrder));
        setField(registry, "watchClient", orderedWatchClient(sequence, watchOrder));

        AutoCloseable handle = registry.watchService("room-service", event -> {
        });
        try {
            assertTrue(watchOrder.get() > 0);
            assertTrue(getOrder.get() > 0);
            assertTrue(watchOrder.get() < getOrder.get());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void opensServiceNameWatchBeforeLoadingInitialSnapshot() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger watchOrder = new AtomicInteger();
        AtomicInteger getOrder = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", orderedEmptyKvClient(sequence, getOrder));
        setField(registry, "watchClient", orderedWatchClient(sequence, watchOrder));

        AutoCloseable handle = registry.watchServiceNames(event -> {
        });
        try {
            assertTrue(watchOrder.get() > 0);
            assertTrue(getOrder.get() > 0);
            assertTrue(watchOrder.get() < getOrder.get());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void serviceNameWatchIgnoresInvalidEtcdKeys() throws Exception {
        AtomicReference<Watch.Listener> listener = new AtomicReference<>();
        AtomicInteger watchCount = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", emptyKvClient());
        setField(registry, "watchClient", recordingWatchClient(listener, watchCount));

        AutoCloseable handle = registry.watchServiceNames(event -> events.incrementAndGet());
        Object registration = watchRegistrations(registry).values().iterator().next();

        try {
            invokeServiceNameWatchResponse(registry, registration, putEvent("/services/ room-service /room-1", "{}"));
            invokeServiceNameWatchResponse(registry, registration, putEvent("/services/room-service/room-1", "{}"));

            assertEquals(1, events.get());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void closesWatcherCreatedAfterRegistryWasClosed() throws Exception {
        AtomicInteger watcherCloses = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", emptyKvClient());
        setField(registry, "watchClient", closingWatchClient(registry, watcherCloses));

        RegistryOperationException failure = assertThrows(
                RegistryOperationException.class,
                () -> registry.watchService("room-service", event -> {
                }));

        assertEquals("Etcd registry has been closed", failure.getMessage());
        assertEquals(1, watcherCloses.get());
        assertTrue(watchRegistrations(registry).isEmpty());
        registry.close();
    }

    @Test
    void ignoresLateServiceWatchEventsAfterHandleClose() throws Exception {
        AtomicReference<Watch.Listener> listener = new AtomicReference<>();
        AtomicInteger watchCount = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", emptyKvClient());
        setField(registry, "watchClient", recordingWatchClient(listener, watchCount));

        AutoCloseable handle = registry.watchService("room-service", event -> events.incrementAndGet());
        Object registration = watchRegistrations(registry).values().iterator().next();
        handle.close();

        invokeServiceWatchResponse(registry, registration, putEvent(
                "/services/room-service/room-2",
                "{\"name\":\"room-service\",\"id\":\"room-2\",\"address\":\"10.0.0.9\",\"port\":9001}"
        ));

        assertEquals(0, events.get());
        assertTrue(watchRegistrations(registry).isEmpty());
        registry.close();
    }

    @Test
    void emitsServiceWatchUpdateOnlyWhenEtcdPutChangesInstance() throws Exception {
        AtomicReference<Watch.Listener> listener = new AtomicReference<>();
        AtomicInteger watchCount = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", emptyKvClient());
        setField(registry, "watchClient", recordingWatchClient(listener, watchCount));

        AutoCloseable handle = registry.watchService("room-service", event -> events.incrementAndGet());
        Object registration = watchRegistrations(registry).values().iterator().next();

        try {
            WatchEvent first = putEvent(
                    "/services/room-service/room-2",
                    "{\"name\":\"room-service\",\"id\":\"room-2\",\"address\":\"10.0.0.9\","
                            + "\"port\":9001,\"weight\":1.0,\"healthy\":true,\"registrationTimeUTC\":100,\"metadata\":{}}"
            );
            WatchEvent changed = putEvent(
                    "/services/room-service/room-2",
                    "{\"name\":\"room-service\",\"id\":\"room-2\",\"address\":\"10.0.0.9\","
                            + "\"port\":9001,\"weight\":2.0,\"healthy\":true,\"registrationTimeUTC\":100,\"metadata\":{}}"
            );

            invokeServiceWatchResponse(registry, registration, first);
            invokeServiceWatchResponse(registry, registration, first);
            invokeServiceWatchResponse(registry, registration, changed);

            assertEquals(2, events.get());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void watchFiltersInvalidEtcdMetadataEntries() throws Exception {
        AtomicReference<Watch.Listener> listener = new AtomicReference<>();
        AtomicInteger watchCount = new AtomicInteger();
        AtomicReference<ServiceInstance> eventInstance = new AtomicReference<>();
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        setField(registry, "started", true);
        setField(registry, "kvClient", emptyKvClient());
        setField(registry, "watchClient", recordingWatchClient(listener, watchCount));

        AutoCloseable handle = registry.watchService("room-service", event -> eventInstance.set(event.getInstance()));
        Object registration = watchRegistrations(registry).values().iterator().next();

        try {
            invokeServiceWatchResponse(registry, registration, putEvent(
                    "/services/room-service/room-2",
                    "{\"name\":\"room-service\",\"id\":\"room-2\",\"address\":\"10.0.0.9\","
                            + "\"port\":9001,\"metadata\":{\"zone\":\"a\",\"bad\":null}}"
            ));

            assertEquals("a", eventInstance.get().getMetadata().get("zone"));
            assertTrue(!eventInstance.get().getMetadata().containsKey("bad"));
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void buildKeyNormalizesBasePath() throws Exception {
        EtcdRegistry registry = new EtcdRegistry(" http://127.0.0.1:2379 ", " /services/// ", 10);
        try {
            assertEquals("/services/room-service/room-1", buildKey(registry, instance()));
        } finally {
            registry.close();
        }
    }

    @Test
    void rejectsInvalidProductionClientOptions() {
        Properties missingClientKey = new Properties();
        missingClientKey.setProperty(EtcdRegistry.PROP_CLIENT_CERT_PATH, "client.crt");
        assertThrows(RegistryOperationException.class,
                () -> new EtcdRegistry("http://127.0.0.1:2379", "/services", 10, missingClientKey));

        Properties blankClientKey = new Properties();
        blankClientKey.setProperty(EtcdRegistry.PROP_CLIENT_CERT_PATH, " client.crt ");
        blankClientKey.setProperty(EtcdRegistry.PROP_CLIENT_KEY_PATH, " ");
        assertThrows(RegistryOperationException.class,
                () -> new EtcdRegistry("http://127.0.0.1:2379", "/services", 10, blankClientKey));

        Properties invalidRetry = new Properties();
        invalidRetry.setProperty(EtcdRegistry.PROP_RETRY_MAX_ATTEMPTS, "0");
        assertThrows(RegistryOperationException.class,
                () -> new EtcdRegistry("http://127.0.0.1:2379", "/services", 10, invalidRetry));

        Properties invalidOperationTimeout = new Properties();
        invalidOperationTimeout.setProperty(EtcdRegistry.PROP_OPERATION_TIMEOUT_MILLIS, "0");
        assertThrows(RegistryOperationException.class,
                () -> new EtcdRegistry("http://127.0.0.1:2379", "/services", 10, invalidOperationTimeout));
    }

    @Test
    void closeIsIdempotentBeforeStart() {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);

        registry.close();
        registry.close();
    }

    @Test
    void rejectsStartAfterClose() {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        registry.close();

        assertThrows(RegistryOperationException.class, registry::start);
    }

    @Test
    void rejectsOperationsAfterCloseWithClosedMessage() {
        EtcdRegistry registry = new EtcdRegistry("http://127.0.0.1:2379", "/services", 10);
        registry.close();

        RegistryOperationException failure = assertThrows(
                RegistryOperationException.class,
                () -> registry.register(instance())
        );

        assertEquals("Etcd registry has been closed", failure.getMessage());
    }

    private String buildKey(EtcdRegistry registry, ServiceInstance instance) throws Exception {
        return invokeStringMethod(registry, "buildKey", instance);
    }

    private String registrationKey(EtcdRegistry registry, ServiceInstance instance) throws Exception {
        return invokeStringMethod(registry, "registrationKey", instance);
    }

    private ServiceInstance deserializeInstance(EtcdRegistry registry, String key, String json) throws Exception {
        Method method = EtcdRegistry.class.getDeclaredMethod("deserializeInstance", KeyValue.class, String.class, String.class);
        method.setAccessible(true);
        return (ServiceInstance) method.invoke(
                registry,
                new KeyValue(apiKeyValue(key, json), ByteSequence.EMPTY),
                "room-service",
                key
        );
    }

    private String invokeStringMethod(EtcdRegistry registry, String methodName, ServiceInstance instance) throws Exception {
        Method method = EtcdRegistry.class.getDeclaredMethod(methodName, ServiceInstance.class);
        method.setAccessible(true);
        return (String) method.invoke(registry, instance);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, ServiceInstance> registeredInstances(EtcdRegistry registry) throws Exception {
        Field field = EtcdRegistry.class.getDeclaredField("registeredInstances");
        field.setAccessible(true);
        return (ConcurrentMap<String, ServiceInstance>) field.get(registry);
    }

    private void setField(EtcdRegistry registry, String name, Object value) throws Exception {
        Field field = EtcdRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private void setLeaseId(EtcdRegistry registry, long value) throws Exception {
        Field field = EtcdRegistry.class.getDeclaredField("leaseId");
        field.setAccessible(true);
        ((AtomicLong) field.get(registry)).set(value);
    }

    private void scheduleReconnection(EtcdRegistry registry) throws Exception {
        Method method = EtcdRegistry.class.getDeclaredMethod("scheduleReconnection");
        method.setAccessible(true);
        method.invoke(registry);
    }

    private AtomicBoolean reconnectScheduled(EtcdRegistry registry) throws Exception {
        Field field = EtcdRegistry.class.getDeclaredField("reconnectScheduled");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(registry);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<Long, Object> watchRegistrations(EtcdRegistry registry) throws Exception {
        Field field = EtcdRegistry.class.getDeclaredField("watchHandles");
        field.setAccessible(true);
        return (ConcurrentMap<Long, Object>) field.get(registry);
    }

    private long watchRetryDelayMs(Object registration) throws Exception {
        Field field = registration.getClass().getDeclaredField("currentRetryDelayMs");
        field.setAccessible(true);
        return (long) field.get(registration);
    }

    private void invokeServiceWatchResponse(EtcdRegistry registry, Object registration, WatchEvent event) throws Exception {
        Method method = EtcdRegistry.class.getDeclaredMethod(
                "handleServiceWatchResponse",
                registration.getClass(),
                List.class
        );
        method.setAccessible(true);
        method.invoke(registry, registration, List.of(event));
    }

    private void invokeServiceNameWatchResponse(EtcdRegistry registry, Object registration, WatchEvent event) throws Exception {
        Method method = EtcdRegistry.class.getDeclaredMethod(
                "handleServiceNameWatchResponse",
                registration.getClass(),
                List.class
        );
        method.setAccessible(true);
        method.invoke(registry, registration, List.of(event));
    }

    private WatchEvent putEvent(String key, String json) {
        KeyValue keyValue = new KeyValue(apiKeyValue(key, json), ByteSequence.EMPTY);
        return new WatchEvent(keyValue, null, WatchEvent.EventType.PUT);
    }

    private io.etcd.jetcd.api.KeyValue apiKeyValue(String key, String json) {
        return io.etcd.jetcd.api.KeyValue.newBuilder()
                .setKey(com.google.protobuf.ByteString.copyFrom(key.getBytes(StandardCharsets.UTF_8)))
                .setValue(com.google.protobuf.ByteString.copyFrom(json.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private long currentRetryDelayMs(EtcdRegistry registry) throws Exception {
        Field field = EtcdRegistry.class.getDeclaredField("currentRetryDelayMs");
        field.setAccessible(true);
        return (long) field.get(registry);
    }

    private ThreadFactory daemonThreadFactory() throws Exception {
        Method method = EtcdRegistry.class.getDeclaredMethod("daemonThreadFactory");
        method.setAccessible(true);
        return (ThreadFactory) method.invoke(null);
    }

    private KV failingKvClient() {
        return (KV) java.lang.reflect.Proxy.newProxyInstance(
                KV.class.getClassLoader(),
                new Class<?>[]{KV.class},
                (proxy, method, args) -> {
                    if ("put".equals(method.getName())) {
                        return CompletableFuture.failedFuture(new IllegalStateException("remote write failed"));
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private KV hangingPutKvClient() {
        return (KV) java.lang.reflect.Proxy.newProxyInstance(
                KV.class.getClassLoader(),
                new Class<?>[]{KV.class},
                (proxy, method, args) -> {
                    if ("put".equals(method.getName())) {
                        return new CompletableFuture<>();
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private KV countingPutKvClient(AtomicInteger putCalls) {
        return (KV) java.lang.reflect.Proxy.newProxyInstance(
                KV.class.getClassLoader(),
                new Class<?>[]{KV.class},
                (proxy, method, args) -> {
                    if ("put".equals(method.getName())) {
                        putCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Lease controllableLeaseClient(
            CountDownLatch grantCalled,
            CompletableFuture<LeaseGrantResponse> grantFuture) {
        return (Lease) java.lang.reflect.Proxy.newProxyInstance(
                Lease.class.getClassLoader(),
                new Class<?>[]{Lease.class},
                (proxy, method, args) -> {
                    if ("grant".equals(method.getName())) {
                        grantCalled.countDown();
                        return grantFuture;
                    }
                    if ("keepAlive".equals(method.getName())) {
                        return new CloseableClient() {
                            @Override
                            public void close() {
                            }
                        };
                    }
                    if ("revoke".equals(method.getName())) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private KV emptyKvClient() {
        return (KV) java.lang.reflect.Proxy.newProxyInstance(
                KV.class.getClassLoader(),
                new Class<?>[]{KV.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        return CompletableFuture.completedFuture(
                                new GetResponse(RangeResponse.newBuilder().build(), ByteSequence.EMPTY)
                        );
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private KV responseKvClient(io.etcd.jetcd.api.KeyValue... keyValues) {
        RangeResponse.Builder range = RangeResponse.newBuilder();
        for (io.etcd.jetcd.api.KeyValue keyValue : keyValues) {
            range.addKvs(keyValue);
        }
        GetResponse response = new GetResponse(range.build(), ByteSequence.EMPTY);
        return (KV) java.lang.reflect.Proxy.newProxyInstance(
                KV.class.getClassLoader(),
                new Class<?>[]{KV.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        return CompletableFuture.completedFuture(response);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private KV orderedEmptyKvClient(AtomicInteger sequence, AtomicInteger getOrder) {
        return (KV) java.lang.reflect.Proxy.newProxyInstance(
                KV.class.getClassLoader(),
                new Class<?>[]{KV.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        getOrder.compareAndSet(0, sequence.incrementAndGet());
                        return CompletableFuture.completedFuture(
                                new GetResponse(RangeResponse.newBuilder().build(), ByteSequence.EMPTY)
                        );
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Watch recordingWatchClient(AtomicReference<Watch.Listener> listener, AtomicInteger watchCount) {
        return (Watch) java.lang.reflect.Proxy.newProxyInstance(
                Watch.class.getClassLoader(),
                new Class<?>[]{Watch.class},
                (proxy, method, args) -> {
                    if ("watch".equals(method.getName()) && args != null && args.length == 3) {
                        listener.set((Watch.Listener) args[2]);
                        watchCount.incrementAndGet();
                        return watcher();
                    }
                    if ("requestProgress".equals(method.getName()) || "close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Watch orderedWatchClient(AtomicInteger sequence, AtomicInteger watchOrder) {
        return (Watch) java.lang.reflect.Proxy.newProxyInstance(
                Watch.class.getClassLoader(),
                new Class<?>[]{Watch.class},
                (proxy, method, args) -> {
                    if ("watch".equals(method.getName()) && args != null && args.length == 3) {
                        watchOrder.compareAndSet(0, sequence.incrementAndGet());
                        return watcher();
                    }
                    if ("requestProgress".equals(method.getName()) || "close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Watch closingWatchClient(EtcdRegistry registry, AtomicInteger watcherCloses) {
        return (Watch) java.lang.reflect.Proxy.newProxyInstance(
                Watch.class.getClassLoader(),
                new Class<?>[]{Watch.class},
                (proxy, method, args) -> {
                    if ("watch".equals(method.getName()) && args != null && args.length == 3) {
                        registry.close();
                        return watcher(watcherCloses);
                    }
                    if ("requestProgress".equals(method.getName()) || "close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Watch.Watcher watcher() {
        return watcher(null);
    }

    private Watch.Watcher watcher(AtomicInteger closes) {
        return (Watch.Watcher) java.lang.reflect.Proxy.newProxyInstance(
                Watch.Watcher.class.getClassLoader(),
                new Class<?>[]{Watch.Watcher.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && closes != null) {
                        closes.incrementAndGet();
                    }
                    return null;
                }
        );
    }

    private ServiceInstance instance(String serviceName, String id, int port) {
        ServiceInstance instance = new ServiceInstance();
        instance.setName(serviceName);
        instance.setId(id);
        instance.setAddress("10.0.0.8");
        instance.setPort(port);
        return instance;
    }

    private ServiceInstance instance() {
        return instance("room-service", "room-1", 9000);
    }
}
