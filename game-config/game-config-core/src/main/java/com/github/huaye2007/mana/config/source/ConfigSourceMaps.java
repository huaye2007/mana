package com.github.huaye2007.mana.config.source;

import java.util.HashMap;
import java.util.Map;

final class ConfigSourceMaps {
    private ConfigSourceMaps() {
    }

    static Map<String, String> immutableCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    static Map<String, String> mutableCopy(Map<String, String> source) {
        return new HashMap<>(immutableCopy(source));
    }
}
