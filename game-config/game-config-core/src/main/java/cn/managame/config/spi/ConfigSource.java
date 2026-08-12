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
