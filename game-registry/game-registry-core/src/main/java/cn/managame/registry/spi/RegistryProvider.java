package cn.managame.registry.spi;

import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;

import java.util.Locale;

public interface RegistryProvider {
    String type();

    RegistryBundle create(RegistryConfig config);

    default boolean supports(String requestedType) {
        return normalize(type()).equals(normalize(requestedType));
    }

    static String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }
}
