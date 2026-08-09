package cn.managame.config.spi;

import cn.managame.config.ConfigLayer;

/** Creates a {@link ConfigSource} for one backend type. Discovered with {@link java.util.ServiceLoader}. */
public interface ConfigProvider {
    /** Backend id matched against {@link ConfigLayer#type()}, for example {@code local} or {@code nacos}. */
    String type();

    /** Creates a source for one layer. Validation of layer settings belongs here, not in the factory. */
    ConfigSource create(ConfigLayer layer);
}
