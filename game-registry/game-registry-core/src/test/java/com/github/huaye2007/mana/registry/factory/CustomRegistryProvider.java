package com.github.huaye2007.mana.registry.factory;

import com.github.huaye2007.mana.registry.spi.RegistryProvider;

public class CustomRegistryProvider implements RegistryProvider {
    static RegistryConfig lastConfig;

    @Override
    public String type() {
        return "custom-test";
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        lastConfig = config;
        config.setType("mutated-test");
        config.setEndpoints("mutated://local");
        config.setProperty("zone", "mutated");
        RegistryFactoryTest.StubRegistry registry = new RegistryFactoryTest.StubRegistry();
        return new RegistryBundle(registry, registry);
    }
}
