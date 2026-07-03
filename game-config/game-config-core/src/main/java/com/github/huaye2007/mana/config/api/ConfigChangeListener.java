package com.github.huaye2007.mana.config.api;

@FunctionalInterface
public interface ConfigChangeListener {
    void onChange(ConfigChangeEvent event);
}
