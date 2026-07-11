package cn.managame.registry.memory;

import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.spi.RegistryProvider;

public final class MemoryRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return "memory";
    }

    @Override
    public ServiceRegistry create(RegistryConfig config) {
        return new MemoryRegistry(config.getEndpoints());
    }
}
