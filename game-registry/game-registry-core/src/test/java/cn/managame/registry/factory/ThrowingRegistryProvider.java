package cn.managame.registry.factory;

import cn.managame.registry.spi.RegistryProvider;

public class ThrowingRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        throw new IllegalStateException("provider type failed");
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        throw new IllegalStateException("provider create should not be called");
    }
}
