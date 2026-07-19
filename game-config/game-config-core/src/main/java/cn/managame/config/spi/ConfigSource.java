package cn.managame.config.spi;

import java.util.Map;
import java.util.function.Consumer;

public interface ConfigSource extends AutoCloseable {
    Map<String, String> load() throws Exception;

    /**
     * Starts the single active watch for this source.
     *
     * <p>The caller must close the returned handle before starting another watch. Implementations
     * publish complete snapshots in source order and report terminal watch failures through
     * {@code onError}.</p>
     */
    AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) throws Exception;

    @Override
    default void close() throws Exception { }
}
