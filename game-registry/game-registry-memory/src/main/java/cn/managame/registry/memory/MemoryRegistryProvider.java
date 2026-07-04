package cn.managame.registry.memory;

import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryType;
import cn.managame.registry.spi.RegistryProvider;

/**
 * SPI provider for the in-memory backend. Unlike real backends the {@code endpoints} field is not
 * a network address — it names the shared in-process namespace (blank means
 * {@link MemoryRegistry#DEFAULT_NAMESPACE}), so bundles built with the same endpoints see each
 * other and different endpoints stay isolated.
 */
public class MemoryRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return RegistryType.MEMORY.type();
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        String endpoints = config.getEndpoints();
        String namespace = endpoints == null || endpoints.isBlank()
                ? MemoryRegistry.DEFAULT_NAMESPACE
                : endpoints.trim();
        MemoryRegistry registry = new MemoryRegistry(namespace);
        return new RegistryBundle(registry, registry);
    }
}
