package com.github.huaye2007.mana.registry.consul;

import com.github.huaye2007.mana.registry.factory.RegistryBundle;
import com.github.huaye2007.mana.registry.factory.RegistryConfig;
import com.github.huaye2007.mana.registry.factory.RegistryType;
import com.github.huaye2007.mana.registry.spi.RegistryProvider;
import com.github.huaye2007.mana.registry.support.RegistryValidators;

public class ConsulRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return RegistryType.CONSUL.type();
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        RegistryValidators.validateEndpoints(config.getEndpoints());
        ConsulRegistry registry = new ConsulRegistry(
                config.getEndpoints(),
                config.getProperties()
        );
        return new RegistryBundle(registry, registry);
    }
}
