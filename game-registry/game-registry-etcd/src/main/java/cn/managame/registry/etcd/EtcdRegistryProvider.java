package cn.managame.registry.etcd;

import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.spi.RegistryProvider;

public final class EtcdRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return "etcd";
    }

    @Override
    public ServiceRegistry create(RegistryConfig config) {
        return new EtcdRegistry(config);
    }
}
