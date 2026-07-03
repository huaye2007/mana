package com.github.huaye2007.mana.registry.etcd;

import com.github.huaye2007.mana.registry.factory.RegistryBundle;
import com.github.huaye2007.mana.registry.factory.RegistryConfig;
import com.github.huaye2007.mana.registry.factory.RegistryType;
import com.github.huaye2007.mana.registry.spi.RegistryProvider;
import com.github.huaye2007.mana.registry.support.RegistryValidators;

public class EtcdRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return RegistryType.ETCD.type();
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        String endpoints = RegistryValidators.normalizeEndpoints(config.getEndpoints());
        String basePath = RegistryValidators.normalizeBasePath(config.getBasePath());
        RegistryValidators.validateLeaseTtlSeconds(config.getLeaseTtlSeconds());
        EtcdRegistry registry = new EtcdRegistry(
                endpoints,
                basePath,
                config.getLeaseTtlSeconds(),
                config.getProperties()
        );
        return new RegistryBundle(registry, registry);
    }
}
