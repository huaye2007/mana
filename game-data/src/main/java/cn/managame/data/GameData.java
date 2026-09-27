package cn.managame.data;

import java.util.Map;

public final class GameData implements AutoCloseable {
    private final Map<Class<?>,Object> repositories;
    final WriteBehindManager writer;
    GameData(Map<Class<?>,Object> repositories, WriteBehindManager writer) {
        this.repositories = Map.copyOf(repositories); this.writer = writer;
    }
    public <R> R repository(Class<R> type) {
        Object repository = repositories.get(type);
        if (repository == null) throw new IllegalArgumentException("Repository is not registered: " + type);
        return type.cast(repository);
    }
    @Override public void close() { writer.close(); }
}
