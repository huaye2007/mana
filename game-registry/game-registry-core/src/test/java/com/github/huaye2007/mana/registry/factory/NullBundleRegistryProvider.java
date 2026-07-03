package com.github.huaye2007.mana.registry.factory;

import com.github.huaye2007.mana.registry.spi.RegistryProvider;

public class NullBundleRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return "null-bundle-test";
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        return null;
    }
}
