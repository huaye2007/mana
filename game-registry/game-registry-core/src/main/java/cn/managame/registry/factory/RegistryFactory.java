package cn.managame.registry.factory;

import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.exception.RegistryException;
import cn.managame.registry.spi.RegistryProvider;

import java.util.Locale;
import java.util.ServiceLoader;

public final class RegistryFactory {
    private RegistryFactory() {
    }

    public static ServiceRegistry startRegistry(RegistryConfig config) {
        String type = normalize(config.getType());
        java.util.List<RegistryProvider> matched = providers().stream()
                .filter(provider -> normalize(provider.type()).equals(type))
                .toList();
        if (matched.isEmpty()) {
            throw new RegistryException("registry provider is not available: " + type);
        }
        if (matched.size() > 1) {
            // 静默选一个会让 classpath 上多出来的 provider 变成一个查不出来的行为差异。
            throw new RegistryException("multiple registry providers declare type '" + type + "': "
                    + matched.stream().map(provider -> provider.getClass().getName()).toList());
        }
        ServiceRegistry registry = matched.getFirst().create(config);
        if (registry == null) {
            throw new RegistryException("registry provider returned null for type: " + type);
        }
        return registry;
    }

    public static boolean isAvailable(String type) {
        String normalized = normalize(type);
        return providers().stream().anyMatch(provider -> normalize(provider.type()).equals(normalized));
    }

    private static java.util.List<RegistryProvider> providers() {
        return ServiceLoader.load(RegistryProvider.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    private static String normalize(String type) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("registry type must not be blank");
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
