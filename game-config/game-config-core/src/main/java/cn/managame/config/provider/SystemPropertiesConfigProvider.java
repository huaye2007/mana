package cn.managame.config.provider;

import cn.managame.config.ConfigLayer;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads JVM system properties as a config layer, so {@code -Dgame.db.url=...} overrides a file or a
 * remote backend without touching either.
 *
 * <p>Property names are already dotted and are used as config keys unchanged. The {@code prefix}
 * property filters which ones are read; set {@code strip} to remove it from the resulting keys.</p>
 *
 * <p>There is no watch: system properties are re-read on every reload, which picks up a late
 * {@code System.setProperty} at the next refresh without a background thread.</p>
 */
public final class SystemPropertiesConfigProvider implements ConfigProvider {
    @Override public String type() { return ConfigLayer.SYSTEM_PROPERTIES; }

    @Override public ConfigSource create(ConfigLayer layer) {
        return new SystemPropertiesSource(layer, System::getProperties);
    }

    static final class SystemPropertiesSource implements ConfigSource {
        private final String prefix;
        private final boolean strip;
        private final Supplier<Properties> properties;

        SystemPropertiesSource(ConfigLayer layer, Supplier<Properties> properties) {
            prefix = layer.property("prefix", "");
            strip = layer.booleanProperty("strip", false);
            this.properties = properties;
        }

        @Override public Map<String, String> load() {
            Properties current = properties.get();
            Map<String, String> selected = new LinkedHashMap<>();
            for (String name : current.stringPropertyNames()) {
                if (!name.startsWith(prefix)) continue;
                String key = strip ? name.substring(prefix.length()) : name;
                if (!key.isEmpty()) selected.put(key, current.getProperty(name));
            }
            return Map.copyOf(selected);
        }

        @Override public void ping() { }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            return () -> { };
        }

        @Override public String toString() { return "SystemPropertiesSource[prefix=" + prefix + "]"; }
    }
}
