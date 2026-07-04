package cn.managame.config.source;

import cn.managame.config.loader.LocalConfigLoader;
import cn.managame.config.loader.LocalConfigLoaderFactory;

import java.util.Map;
import java.util.Objects;

/**
 * 本地文件配置源。根据文件扩展名自动选择合适的 loader（properties/json/conf 等）。
 */
public class LocalFileConfigSource implements ConfigSource {
    private final LocalConfigLoader loader;

    public LocalFileConfigSource(String filePath) {
        this.loader = LocalConfigLoaderFactory.create(filePath);
    }

    public LocalFileConfigSource(LocalConfigLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    @Override
    public String name() {
        return "LOCAL_FILE";
    }

    @Override
    public Map<String, String> load() {
        return ConfigSourceMaps.immutableCopy(loader.load());
    }
}
