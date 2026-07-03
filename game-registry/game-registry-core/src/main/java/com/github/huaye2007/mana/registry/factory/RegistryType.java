package com.github.huaye2007.mana.registry.factory;

import java.util.Locale;

public enum RegistryType {
    ZOOKEEPER("zookeeper"),
    ETCD("etcd"),
    NACOS("nacos"),
    CONSUL("consul");

    private final String type;

    RegistryType(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    public static RegistryType fromType(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        for (RegistryType registryType : values()) {
            if (registryType.type.equals(normalized)) {
                return registryType;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return type;
    }
}
