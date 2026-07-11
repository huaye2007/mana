package cn.managame.registry.factory;

import java.util.Locale;

public enum RegistryType {
    MEMORY,
    NACOS,
    ETCD;

    public String type() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RegistryType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("registry type must not be blank");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
