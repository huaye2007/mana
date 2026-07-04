package cn.managame.registry.starter;

import cn.managame.registry.api.Discovery;
import cn.managame.registry.api.Registry;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.exception.RegistryOperationException;
import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.spi.RegistryProvider;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameRegistryStarterTest {
    @Test
    void builderBuildsConfigWithCommonOptions() {
        Properties source = new Properties();
        source.setProperty("namespace", "game");

        RegistryConfig config = GameRegistryStarter.builder()
                .type("starter-test")
                .endpoints("custom://local")
                .basePath("/game/services")
                .leaseTtlSeconds(30)
                .properties(source)
                .property("zone", "shanghai-a")
                .buildConfig();

        source.setProperty("namespace", "changed");
        Properties returned = config.getProperties();
        returned.setProperty("zone", "changed");

        assertEquals("starter-test", config.getTypeName());
        assertEquals("custom://local", config.getEndpoints());
        assertEquals("/game/services", config.getBasePath());
        assertEquals(30L, config.getLeaseTtlSeconds());
        assertEquals("game", config.getProperties().getProperty("namespace"));
        assertEquals("shanghai-a", config.getProperties().getProperty("zone"));
    }

    @Test
    void createUsesRegistryFactoryWithoutStartingBundle() {
        RegistryBundle bundle = GameRegistryStarter.create("starter-test", "custom://local");
        TestEndpoint endpoint = (TestEndpoint) bundle.getRegistry();

        assertNotNull(bundle.getDiscovery());
        assertEquals(0, endpoint.starts);

        bundle.close();
    }

    @Test
    void builderRejectsInvalidPropertyEntries() {
        GameRegistryStarter.Builder builder = GameRegistryStarter.builder();

        assertThrows(RegistryOperationException.class, () -> builder.property(null, "value"));
        assertThrows(RegistryOperationException.class, () -> builder.property(" ", "value"));
        assertThrows(RegistryOperationException.class, () -> builder.property("zone", null));
    }

    @Test
    void builderRejectsInvalidBulkPropertyEntriesWithoutClearingExistingProperties() {
        GameRegistryStarter.Builder builder = GameRegistryStarter.builder()
                .property("zone", "a");
        Properties blankKey = new Properties();
        blankKey.setProperty(" ", "value");
        Properties nonStringValue = new Properties();
        nonStringValue.put("zone", 1);
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("zone", null);

        assertThrows(RegistryOperationException.class, () -> builder.properties(blankKey));
        assertThrows(RegistryOperationException.class, () -> builder.properties(nonStringValue));
        assertThrows(RegistryOperationException.class, () -> builder.properties(nullValue));
        assertEquals("a", builder.buildConfig().getProperties().getProperty("zone"));
    }

    @Test
    void startUsesRegistryFactoryAndStartsBundle() {
        RegistryBundle bundle = GameRegistryStarter.builder()
                .type("starter-test")
                .endpoints("custom://local")
                .properties(Map.of("region", "cn-east"))
                .start();
        TestEndpoint endpoint = (TestEndpoint) bundle.getRegistry();

        assertEquals(1, endpoint.starts);
        assertEquals("cn-east", TestRegistryProvider.lastConfig.getProperties().getProperty("region"));

        bundle.close();
        assertEquals(1, endpoint.closes);
    }

    public static final class TestRegistryProvider implements RegistryProvider {
        private static RegistryConfig lastConfig;

        @Override
        public String type() {
            return "starter-test";
        }

        @Override
        public RegistryBundle create(RegistryConfig config) {
            lastConfig = config;
            TestEndpoint endpoint = new TestEndpoint();
            return new RegistryBundle(endpoint, endpoint);
        }
    }

    static class TestEndpoint implements Registry, Discovery {
        private int starts;
        private int closes;

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
            starts++;
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
