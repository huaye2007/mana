package cn.managame.registry.nacos;

import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.spi.RegistryProvider;

public final class NacosRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return "nacos";
    }

    @Override
    public ServiceRegistry create(RegistryConfig config) {
        return new NacosRegistry(config);
    }
}
