package com.github.huaye2007.mana.config.source;

import java.util.Map;

/**
 * 默认值配置源，作为兜底。
 */
public class DefaultConfigSource implements ConfigSource {
    private final Map<String, String> defaults;

    public DefaultConfigSource(Map<String, String> defaults) {
        this.defaults = ConfigSourceMaps.immutableCopy(defaults);
    }

    @Override
    public String name() {
        return "DEFAULT";
    }

    @Override
    public Map<String, String> load() {
        return defaults;
    }
}
