package cn.managame.config;

import java.util.Set;

public record ConfigChange(ConfigSnapshot previous, ConfigSnapshot current, Set<String> changedKeys) {
    public ConfigChange {
        java.util.Objects.requireNonNull(previous, "previous");
        java.util.Objects.requireNonNull(current, "current");
        changedKeys = Set.copyOf(changedKeys);
    }
}
