package cn.managame.registry.zookeeper;

import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryType;
import cn.managame.registry.spi.RegistryProvider;
import cn.managame.registry.support.RegistryValidators;

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
