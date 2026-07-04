package cn.managame.registry.consul;

import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryType;
import cn.managame.registry.spi.RegistryProvider;
import cn.managame.registry.support.RegistryValidators;

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
