package cn.managame.registry.factory;

import cn.managame.registry.api.Discovery;
import cn.managame.registry.api.Registry;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryFactoryTest {
    @Test
    void exposesAvailableProviderTypes() {
        List<String> types = RegistryFactory.availableTypes();
        List<String> sorted = new ArrayList<>(types);
        sorted.sort(String::compareTo);

        assertEquals(sorted, types);
        assertEquals(new HashSet<>(types).size(), types.size());
        assertTrue(types.contains("custom-test"));
        assertFalse(types.contains("memory"));
        assertFalse(RegistryFactory.isAvailable(" memory "));
        assertFalse(RegistryFactory.isAvailable(" inmemory "));
        assertTrue(RegistryFactory.isAvailable("custom-test"));
        assertFalse(RegistryFactory.isAvailable("in-memory"));
        assertFalse(RegistryFactory.isAvailable(" "));
        assertFalse(RegistryFactory.isAvailable("missing-test"));
        assertThrows(UnsupportedOperationException.class, () -> types.add("other"));
    }

    @Test
    void missingProviderErrorListsStableAvailableTypes() {
        RegistryConfig config = new RegistryConfig();
        config.setType("missing-test");

        RegistryOperationException failure = assertThrows(
                RegistryOperationException.class,
                () -> RegistryFactory.create(config)
        );

        assertTrue(failure.getMessage().contains("No registry provider found for type: missing-test"));
        assertTrue(failure.getMessage().contains("available types: " + RegistryFactory.availableTypes()));
    }

    @Test
    void ignoresBrokenProvidersDuringDiscovery() {
        assertTrue(RegistryFactory.availableTypes().contains("custom-test"));
        assertFalse(RegistryFactory.isAvailable("missing-test"));
    }

    @Test
    void createsProviderByExternalStringType() {
        RegistryConfig config = new RegistryConfig();
        config.setType("  Custom-Test  ");

        RegistryBundle bundle = RegistryFactory.create(config);

        assertNotNull(bundle.getRegistry());
        assertNotNull(bundle.getDiscovery());
    }

    @Test
    void passesDefensiveConfigCopyToProvider() {
        RegistryConfig config = RegistryConfig.builder()
                .type("custom-test")
                .endpoints("custom://local")
                .property("zone", "a")
                .build();

        RegistryBundle bundle = RegistryFactory.create(config);

        assertNotSame(config, CustomRegistryProvider.lastConfig);
        assertEquals("custom-test", config.getTypeName());
        assertEquals("custom://local", config.getEndpoints());
        assertEquals("a", config.getProperties().getProperty("zone"));
        assertEquals("mutated-test", CustomRegistryProvider.lastConfig.getTypeName());
        assertEquals("mutated", CustomRegistryProvider.lastConfig.getProperties().getProperty("zone"));
        bundle.close();
    }

    @Test
    void memoryProviderHasBeenRemoved() {
        RegistryConfig config = new RegistryConfig();
        config.setType("memory");

        assertEquals(null, config.getType());
        assertThrows(RegistryOperationException.class, () -> RegistryFactory.create(config));

        RegistryConfig aliasConfig = new RegistryConfig();
        aliasConfig.setType(" inmemory ");

        assertEquals(null, aliasConfig.getType());
        assertThrows(RegistryOperationException.class, () -> RegistryFactory.create(aliasConfig));
    }

    @Test
    void keepsRegistryTypeAsCompatibilityAlias() {
        RegistryConfig config = new RegistryConfig();

        config.setType(RegistryType.ZOOKEEPER);

        assertEquals(RegistryType.ZOOKEEPER, config.getType());
        assertEquals("zookeeper", config.getTypeName());

        config.setType(" consul ");

        assertEquals(RegistryType.CONSUL, config.getType());
        assertEquals(" consul ", config.getTypeName());
    }

    @Test
    void rejectsBlankRegistryType() {
        RegistryConfig config = new RegistryConfig();
        config.setType(" ");

        assertThrows(RegistryOperationException.class, () -> RegistryFactory.create(config));
    }

    @Test
    void propertiesAreDefensivelyCopied() {
        RegistryConfig config = new RegistryConfig();
        Properties properties = new Properties();
        properties.setProperty("namespace", "game");

        config.setProperties(properties);
        properties.setProperty("namespace", "changed");

        Properties returned = config.getProperties();
        returned.setProperty("namespace", "mutated");

        assertEquals("game", config.getProperties().getProperty("namespace"));

        config.setProperties(null);
        assertTrue(config.getProperties().isEmpty());
    }

    @Test
    void rejectsInvalidPropertyEntries() {
        RegistryConfig config = new RegistryConfig();

        assertThrows(RegistryOperationException.class, () -> config.setProperty(null, "value"));
        assertThrows(RegistryOperationException.class, () -> config.setProperty(" ", "value"));
        assertThrows(RegistryOperationException.class, () -> config.setProperty("zone", null));
        assertThrows(RegistryOperationException.class,
                () -> RegistryConfig.builder().property("zone", null));
    }

    @Test
    void rejectsInvalidBulkPropertyEntriesWithoutMutatingExistingProperties() {
        RegistryConfig config = new RegistryConfig();
        config.setProperty("zone", "a");
        Properties blankKey = new Properties();
        blankKey.setProperty(" ", "value");
        Properties nonStringKey = new Properties();
        nonStringKey.put(1, "value");
        Properties nonStringValue = new Properties();
        nonStringValue.put("zone", 1);
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("zone", null);

        assertThrows(RegistryOperationException.class, () -> config.setProperties(blankKey));
        assertThrows(RegistryOperationException.class, () -> config.setProperties(nonStringKey));
        assertThrows(RegistryOperationException.class, () -> config.setProperties(nonStringValue));
        assertThrows(RegistryOperationException.class,
                () -> RegistryConfig.builder().properties(nullValue));
        assertEquals("a", config.getProperties().getProperty("zone"));
    }

    @Test
    void configCopyCreatesDefensivePropertiesCopy() {
        RegistryConfig config = RegistryConfig.builder()
                .type(RegistryType.ETCD)
                .endpoints("http://127.0.0.1:2379")
                .basePath("/game/services")
                .leaseTtlSeconds(30)
                .properties(Map.of("zone", "a"))
                .build();

        RegistryConfig copy = config.copy();
        copy.setProperty("zone", "b");

        assertEquals(RegistryType.ETCD, copy.getType());
        assertEquals("http://127.0.0.1:2379", copy.getEndpoints());
        assertEquals("/game/services", copy.getBasePath());
        assertEquals(30L, copy.getLeaseTtlSeconds());
        assertEquals("a", config.getProperties().getProperty("zone"));
        assertEquals("b", copy.getProperties().getProperty("zone"));
    }

    @Test
    void configBuilderBuildReturnsSnapshot() {
        RegistryConfig.Builder builder = RegistryConfig.builder()
                .type("custom-test")
                .endpoints("custom://local")
                .property("region", "cn-east");

        RegistryConfig first = builder.build();
        RegistryConfig second = builder.property("region", "cn-north").build();

        assertEquals("cn-east", first.getProperties().getProperty("region"));
        assertEquals("cn-north", second.getProperties().getProperty("region"));
    }

    @Test
    void rejectsInvalidRegistryBundles() {
        StubRegistry registry = new StubRegistry();

        assertThrows(RegistryOperationException.class, () -> new RegistryBundle(null, registry));
        assertThrows(RegistryOperationException.class, () -> new RegistryBundle(registry, null));
    }

    @Test
    void rejectsProviderReturningNullBundle() {
        RegistryConfig config = new RegistryConfig();
        config.setType("null-bundle-test");

        assertThrows(RegistryOperationException.class, () -> RegistryFactory.create(config));
    }

    @Test
    void bundleStartsAndClosesSharedEndpointOnce() {
        RecordingEndpoint endpoint = new RecordingEndpoint("shared", new ArrayList<>());
        RegistryBundle bundle = new RegistryBundle(endpoint, endpoint);

        bundle.start();
        bundle.start();
        bundle.close();
        bundle.close();

        assertEquals(1, endpoint.starts);
        assertEquals(1, endpoint.closes);
    }

    @Test
    void bundleStartsAndClosesSeparateEndpointsInOrder() {
        List<String> events = new ArrayList<>();
        RecordingEndpoint registry = new RecordingEndpoint("registry", events);
        RecordingEndpoint discovery = new RecordingEndpoint("discovery", events);
        RegistryBundle bundle = new RegistryBundle(registry, discovery);

        bundle.start();
        bundle.close();

        assertEquals(List.of(
                "registry:start",
                "discovery:start",
                "discovery:close",
                "registry:close"
        ), events);
    }

    @Test
    void bundleClosesRegistryWhenDiscoveryStartFails() {
        List<String> events = new ArrayList<>();
        RecordingEndpoint registry = new RecordingEndpoint("registry", events);
        RecordingEndpoint discovery = new RecordingEndpoint("discovery", events);
        discovery.failOnStart = true;
        RegistryBundle bundle = new RegistryBundle(registry, discovery);

        assertThrows(RegistryOperationException.class, bundle::start);
        assertEquals(List.of(
                "registry:start",
                "discovery:start",
                "discovery:close",
                "registry:close"
        ), events);
    }

    @Test
    void bundleReportsCloseFailureButStillClosesBothEndpoints() {
        List<String> events = new ArrayList<>();
        RecordingEndpoint registry = new RecordingEndpoint("registry", events);
        RecordingEndpoint discovery = new RecordingEndpoint("discovery", events);
        discovery.failOnClose = true;
        RegistryBundle bundle = new RegistryBundle(registry, discovery);

        bundle.start();
        RuntimeException failure = assertThrows(RuntimeException.class, bundle::close);

        assertEquals("discovery failed to close", failure.getMessage());
        assertEquals(List.of(
                "registry:start",
                "discovery:start",
                "discovery:close",
                "registry:close"
        ), events);
    }

    @Test
    void bundleSuppressesSecondCloseFailure() {
        List<String> events = new ArrayList<>();
        RecordingEndpoint registry = new RecordingEndpoint("registry", events);
        RecordingEndpoint discovery = new RecordingEndpoint("discovery", events);
        registry.failOnClose = true;
        discovery.failOnClose = true;
        RegistryBundle bundle = new RegistryBundle(registry, discovery);

        bundle.start();
        RuntimeException failure = assertThrows(RuntimeException.class, bundle::close);

        assertEquals("discovery failed to close", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("registry failed to close", failure.getSuppressed()[0].getMessage());
        assertEquals(List.of(
                "registry:start",
                "discovery:start",
                "discovery:close",
                "registry:close"
        ), events);
    }

    @Test
    void bundleDoesNotCloseUnstartedDiscoveryWhenRegistryStartFails() {
        List<String> events = new ArrayList<>();
        RecordingEndpoint registry = new RecordingEndpoint("registry", events);
        RecordingEndpoint discovery = new RecordingEndpoint("discovery", events);
        registry.failOnStart = true;
        RegistryBundle bundle = new RegistryBundle(registry, discovery);

        assertThrows(RegistryOperationException.class, bundle::start);
        assertEquals(List.of(
                "registry:start",
                "registry:close"
        ), events);
    }

    @Test
    void bundleRejectsStartAfterClose() {
        RecordingEndpoint endpoint = new RecordingEndpoint("shared", new ArrayList<>());
        RegistryBundle bundle = new RegistryBundle(endpoint, endpoint);

        bundle.close();

        assertThrows(RegistryOperationException.class, bundle::start);
        assertEquals(0, endpoint.starts);
        assertEquals(1, endpoint.closes);
    }

    @Test
    void bundleExposesLifecycleState() throws Exception {
        DiagnosticEndpoint endpoint = new DiagnosticEndpoint();
        RegistryBundle bundle = new RegistryBundle(endpoint, endpoint);

        assertFalse(bundle.isStarted());
        assertFalse(bundle.isClosed());

        bundle.start();
        AutoCloseable watch = endpoint.openWatch();
        try {
            assertTrue(bundle.isStarted());
            assertEquals(1, endpoint.getActiveWatchCount());
        } finally {
            watch.close();
            bundle.close();
        }

        assertTrue(bundle.isClosed());
        assertFalse(bundle.isStarted());
    }

    static class StubRegistry implements Registry, Discovery {
        @Override
        public void register(ServiceInstance serviceInstance) {
        }

        @Override
        public void unregister(ServiceInstance serviceInstance) {
        }

        @Override
        public Collection<ServiceInstance> getInstances(String serviceName) {
            return List.of();
        }

        @Override
        public Collection<String> getServiceNames() {
            return List.of();
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    }

    static class RecordingEndpoint extends StubRegistry {
        private final String name;
        private final List<String> events;
        private int starts;
        private int closes;
        private boolean failOnStart;
        private boolean failOnClose;

        RecordingEndpoint(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void start() {
            starts++;
            events.add(name + ":start");
            if (failOnStart) {
                throw new RegistryOperationException(name + " failed to start");
            }
        }

        @Override
        public void close() {
            closes++;
            events.add(name + ":close");
            if (failOnClose) {
                throw new RegistryOperationException(name + " failed to close");
            }
        }
    }

    static class DiagnosticEndpoint extends StubRegistry {
        private int activeWatches;
        private boolean started;
        private boolean closed;

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void close() {
            closed = true;
            started = false;
        }

        public boolean isStarted() {
            return started;
        }

        public boolean isClosed() {
            return closed;
        }

        public int getActiveWatchCount() {
            return activeWatches;
        }

        AutoCloseable openWatch() {
            activeWatches++;
            return () -> activeWatches--;
        }
    }
}
