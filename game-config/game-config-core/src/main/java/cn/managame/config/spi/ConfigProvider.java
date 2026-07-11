package cn.managame.config.spi;

import cn.managame.config.ConfigOptions;

public interface ConfigProvider {
    String type();
    ConfigSource create(ConfigOptions options);
}
