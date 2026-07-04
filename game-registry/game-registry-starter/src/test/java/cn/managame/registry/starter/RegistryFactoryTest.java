package cn.managame.registry.starter;

import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryFactoryTest {
    @Test
    void shouldExposeOnlyProvidersOnStarterClasspath() {
        List<String> types = RegistryFactory.availableTypes();
        List<String> sorted = new ArrayList<>(types);
        sorted.sort(String::compareTo);

        assertTrue(types.equals(sorted));
        assertTrue(types.contains("starter-test"));
        assertFalse(types.contains("zookeeper"));
        assertFalse(types.contains("etcd"));
        assertFalse(types.contains("nacos"));
        assertFalse(types.contains("consul"));
        assertFalse(types.contains("memory"));
        assertFalse(RegistryFactory.isAvailable("memory"));
        assertTrue(RegistryFactory.isAvailable("starter-test"));
        assertFalse(RegistryFactory.isAvailable("zookeeper"));
        assertFalse(RegistryFactory.isAvailable("etcd"));
        assertFalse(RegistryFactory.isAvailable("nacos"));
        assertFalse(RegistryFactory.isAvailable("consul"));
    }

    @Test
    void shouldCreateProviderBySpi() {
        RegistryConfig config = new RegistryConfig();
        config.setType("starter-test");
        config.setEndpoints("custom://local");
        RegistryBundle bundle = RegistryFactory.create(config);
        assertNotNull(bundle);
        assertNotNull(bundle.getRegistry());
        assertNotNull(bundle.getDiscovery());
    }
}
