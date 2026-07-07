package cn.managame.registry.spi;

import cn.managame.common.lang.Strings;
import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryConfig;

public interface RegistryProvider {
    String type();

    RegistryBundle create(RegistryConfig config);

    default boolean supports(String requestedType) {
        return normalize(type()).equals(normalize(requestedType));
    }

    static String normalize(String type) {
        String normalized = Strings.normalizeToLower(type);
        return normalized == null ? "" : normalized;
    }
}
