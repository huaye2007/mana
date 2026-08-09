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
     * Cheaply confirms the backend is reachable, throwing when it is not.
     *
     * <p>Called on the health-check interval, which is far more often than a full reload. The default
     * implementation falls back to {@link #loadData()}; implementations backed by a remote service
     * should override it with a request that transfers no document content, so a large fleet does not
     * turn liveness checks into read load.</p>
     */
    default void ping() throws Exception {
        loadData();
    }

    /**
     * Maps each key of the current merged view to the name of the layer that won it.
     *
     * <p>Only a source composed of several layers can answer this; a single source returns empty and
     * the center attributes every key to it. Diagnostic, not a hot path.</p>
     */
    default Map<String, String> origins() { return Map.of(); }

    /**
     * Every layer that supplies {@code key}, in increasing precedence order, so the last entry is the
     * value actually visible. Empty when the key is absent or the source has no layers.
     */
    default java.util.List<cn.managame.config.ConfigOrigin> explain(String key) { return java.util.List.of(); }

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
