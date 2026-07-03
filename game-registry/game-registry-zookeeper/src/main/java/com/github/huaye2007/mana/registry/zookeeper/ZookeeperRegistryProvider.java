package com.github.huaye2007.mana.registry.zookeeper;

import com.github.huaye2007.mana.registry.factory.RegistryBundle;
import com.github.huaye2007.mana.registry.factory.RegistryConfig;
import com.github.huaye2007.mana.registry.factory.RegistryType;
import com.github.huaye2007.mana.registry.spi.RegistryProvider;
import com.github.huaye2007.mana.registry.support.RegistryValidators;

public class ZookeeperRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return RegistryType.ZOOKEEPER.type();
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        String endpoints = RegistryValidators.normalizeEndpoints(config.getEndpoints());
        String basePath = RegistryValidators.normalizeBasePath(config.getBasePath());
        ZookeeperRegistry registry = new ZookeeperRegistry(
                endpoints,
                basePath,
                config.getProperties()
        );
        return new RegistryBundle(registry, registry);
    }
}
