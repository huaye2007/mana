package cn.managame.data;

import java.util.List;

/** What {@link GameData#stats()} read: one entry per entity repository, those by id first, and one per log repository. */
public record DataStats(List<EntityStats> entities, List<LogStats> logs) {
    public DataStats {
        entities = List.copyOf(entities);
        logs = List.copyOf(logs);
    }
}
