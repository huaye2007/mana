package cn.managame.config.spi;

import java.util.Map;
import java.util.function.Consumer;

public interface ConfigSource extends AutoCloseable {
    Map<String, String> load() throws Exception;

    /** Loads a complete snapshot. Providers with a source revision should override this method. */
    default ConfigData loadData() throws Exception {
        return ConfigData.unversioned(load());
    }

    /**
     * Starts the single active watch for this source.
     *
     * <p>The caller must close the returned handle before starting another watch. Implementations
     * publish complete snapshots in source order and report terminal watch failures through
     * {@code onError}.</p>
     */
    AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) throws Exception;

    /** Watches complete snapshots, preserving source revisions when the provider supports them. */
    default AutoCloseable watchData(Consumer<ConfigData> onUpdate, Consumer<Throwable> onError) throws Exception {
        return watch(values -> onUpdate.accept(ConfigData.unversioned(values)), onError);
    }

    @Override
    default void close() throws Exception { }
}
