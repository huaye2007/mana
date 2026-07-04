package cn.managame.registry.etcd;

import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryType;
import cn.managame.registry.spi.RegistryProvider;
import cn.managame.registry.support.RegistryValidators;

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
