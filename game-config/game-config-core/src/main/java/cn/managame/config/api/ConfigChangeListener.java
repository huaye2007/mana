package cn.managame.config.api;

@FunctionalInterface
public interface ConfigChangeListener {
    void onChange(ConfigChangeEvent event);
}
