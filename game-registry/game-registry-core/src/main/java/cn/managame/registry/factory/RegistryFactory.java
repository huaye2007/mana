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
        return providers().stream()
                .filter(provider -> normalize(provider.type()).equals(type))
                .findFirst()
                .orElseThrow(() -> new RegistryException("registry provider is not available: " + type))
                .create(config);
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
