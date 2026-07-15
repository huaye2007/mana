package cn.managame.config;

import java.util.function.Consumer;
import java.util.Optional;

public interface ConfigCenter extends AutoCloseable {
    ConfigSnapshot snapshot();

    ConfigSnapshot reload();

    AutoCloseable listen(Consumer<ConfigChange> listener);

    default boolean isHealthy() { return true; }

    default Optional<Throwable> lastError() { return Optional.empty(); }

    @Override
    void close();
}
