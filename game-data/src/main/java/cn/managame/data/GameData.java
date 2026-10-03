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
    /** Flushes both buffers synchronously; first quiesce writers for a stable persistence barrier. */
    public void flush() { writer.flushNow(); }
    public DataStats stats() {
        long singles = 0, groups = 0;
        for (Object repository : repositories.values()) {
            if (repository instanceof SingleRepository<?, ?> single) singles += single.cachedEntries();
            if (repository instanceof GroupRepository<?, ?> group) groups += group.cachedEntries();
        }
        return writer.stats(singles, groups);
    }
    @Override public void close() { writer.close(); }
}
