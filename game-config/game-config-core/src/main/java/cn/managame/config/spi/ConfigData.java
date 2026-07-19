package cn.managame.config.spi;

import java.util.Map;

/** A complete source snapshot with an optional monotonic source revision. */
public record ConfigData(long revision, Map<String, String> values) {
    public static final long UNVERSIONED = -1;

    public ConfigData {
        if (revision < UNVERSIONED) throw new IllegalArgumentException("revision must be non-negative or UNVERSIONED");
        values = Map.copyOf(values);
    }

    public static ConfigData unversioned(Map<String, String> values) {
        return new ConfigData(UNVERSIONED, values);
    }

    public boolean isVersioned() {
        return revision != UNVERSIONED;
    }
}
