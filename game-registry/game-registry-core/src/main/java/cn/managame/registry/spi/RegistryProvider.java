package cn.managame.registry.spi;

import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.factory.RegistryConfig;

public interface RegistryProvider {
    String type();

    ServiceRegistry create(RegistryConfig config);
}
