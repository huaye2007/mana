package cn.managame.config.provider;

import cn.managame.config.ConfigLayer;
import cn.managame.config.source.MemoryConfigSource;
import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * In-memory layer, for defaults declared in code and for tests.
 *
 * <p>With a {@code name} property the layer binds to the shared
 * {@link MemoryConfigSource#named(String)} instance, so a test can publish updates into a running
 * center. Without one, the remaining layer properties are the values, which makes
 * {@link ConfigLayer#inline(Map)} a compact way to state defaults underneath the real backends.</p>
 */
public final class MemoryConfigProvider implements ConfigProvider {
    /** Layer property selecting a shared {@link MemoryConfigSource#named(String)} instance. */
    public static final String NAME_PROPERTY = "name";

    @Override public String type() { return ConfigLayer.MEMORY; }

    @Override public ConfigSource create(ConfigLayer layer) {
        String name = layer.property(NAME_PROPERTY, null);
        if (name == null || name.isBlank()) return new MemoryConfigSource(inlineValues(layer));
        // The registered source is owned by whoever registered it, so the center must not close it.
        return new SharedSource(MemoryConfigSource.named(name));
    }

    private static Map<String, String> inlineValues(ConfigLayer layer) {
        Map<String, String> values = new LinkedHashMap<>(layer.properties());
        values.remove(NAME_PROPERTY);
        return values;
    }

    /** Delegates everything but {@code close}, which stays with the owner of the shared source. */
    private record SharedSource(MemoryConfigSource delegate) implements ConfigSource {
        @Override public Map<String, String> load() throws Exception { return delegate.load(); }
        @Override public ConfigData loadData() throws Exception { return delegate.loadData(); }
        @Override public void ping() { }
        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            return delegate.watch(onUpdate, onError);
        }
        @Override public void close() { }
    }
}
