package cn.managame.registry.factory;

import cn.managame.registry.spi.RegistryProvider;

public class NullBundleRegistryProvider implements RegistryProvider {
    @Override
    public String type() {
        return "null-bundle-test";
    }

    @Override
    public RegistryBundle create(RegistryConfig config) {
        return null;
    }
}
