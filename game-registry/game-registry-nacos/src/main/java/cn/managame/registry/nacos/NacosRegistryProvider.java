package cn.managame.registry.nacos;

import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryType;
import cn.managame.registry.spi.RegistryProvider;
import cn.managame.registry.support.RegistryValidators;

public class NacosRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return RegistryType.NACOS.type();
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        RegistryValidators.validateEndpoints(config.getEndpoints());
        NacosRegistry registry = new NacosRegistry(
                config.getEndpoints(),
                config.getProperties()
        );
        return new RegistryBundle(registry, registry);
    }
}
