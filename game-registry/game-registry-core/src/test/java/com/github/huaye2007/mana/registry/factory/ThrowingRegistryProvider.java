package com.github.huaye2007.mana.registry.factory;

import com.github.huaye2007.mana.registry.spi.RegistryProvider;

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
