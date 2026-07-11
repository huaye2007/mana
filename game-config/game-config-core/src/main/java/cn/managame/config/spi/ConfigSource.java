package cn.managame.config.spi;

import java.util.Map;
import java.util.function.Consumer;

public interface ConfigSource extends AutoCloseable {
    Map<String, String> load() throws Exception;

    AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) throws Exception;

    @Override
    default void close() throws Exception { }
}
