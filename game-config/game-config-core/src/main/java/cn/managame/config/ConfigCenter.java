package cn.managame.config;

import java.util.function.Consumer;

public interface ConfigCenter extends AutoCloseable {
    ConfigSnapshot snapshot();

    ConfigSnapshot reload();

    AutoCloseable listen(Consumer<ConfigChange> listener);

    @Override
    void close();
}
