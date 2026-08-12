package cn.managame.config.spi;

import cn.managame.config.ConfigOptions;

/** Creates a {@link ConfigSource} for one backend type. Discovered with {@link java.util.ServiceLoader}. */
public interface ConfigProvider {
    /** Backend id matched against {@link ConfigOptions#type()}, for example {@code local} or {@code nacos}. */
    String type();

    /** Creates the source. Validation of backend settings belongs here, not in the factory. */
    ConfigSource create(ConfigOptions options);
}
