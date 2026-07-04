package cn.managame.config.source;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * JVM 系统属性配置源。可选择是否包含 System.getProperties()，也可注入自定义 JVM 属性。
 */
public class JvmConfigSource implements ConfigSource {
    private final boolean includeSystemProperties;
    private final Map<String, String> customProperties;

    public JvmConfigSource(boolean includeSystemProperties, Map<String, String> customProperties) {
        this.includeSystemProperties = includeSystemProperties;
        this.customProperties = ConfigSourceMaps.immutableCopy(customProperties);
    }

    public JvmConfigSource(boolean includeSystemProperties) {
        this(includeSystemProperties, Map.of());
    }

    @Override
    public String name() {
        return "JVM";
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> result = new HashMap<>();
        if (includeSystemProperties) {
            Properties sysProps = System.getProperties();
            for (String key : sysProps.stringPropertyNames()) {
                result.put(key, sysProps.getProperty(key));
            }
        }
        result.putAll(customProperties);
        return result;
    }
}
