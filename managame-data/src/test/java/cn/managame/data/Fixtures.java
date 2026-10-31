package cn.managame.data;

import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Entities, repositories and an error handler the core tests share. */
final class Fixtures {
    private Fixtures() {}

    static class Player {
        @Id long id;
        String name;
        int level;
        long gold;
        Map<Integer, Long> items = new ConcurrentHashMap<>();
        transient int power;   // not saved

        Player() {}

        Player(long id, String name) {
            this.id = id;
            this.name = name;
            level = 1;
        }
    }

    static class Item {
        @Id long uid;
        @GroupKey long playerId;
        @MapKey int slot;
        int configId;
        int amount;

        Item() {}

        Item(long uid, long playerId, int slot, int configId, int amount) {
            this.uid = uid;
            this.playerId = playerId;
            this.slot = slot;
            this.configId = configId;
            this.amount = amount;
        }
    }

    /** Grouped by player and kind, keyed by quest and step. */
    static class Quest {
        @Id long id;
        @GroupKey(order = 1) long playerId;
        @GroupKey(order = 2) int kind;
        @MapKey(order = 1) int questId;
        @MapKey(order = 2) int step;
        int progress;

        Quest() {}

        Quest(long id, long playerId, int kind, int questId, int step) {
            this.id = id;
            this.playerId = playerId;
            this.kind = kind;
            this.questId = questId;
            this.step = step;
        }
    }

    record ItemLog(long playerId, int configId, int delta) {}

    static final class Players extends SingleRepository<Long, Player> {}

    static final class Items extends GroupRepository<Integer, Item> {}

    static final class Quests extends GroupRepository<String, Quest> {}

    static final class ItemLogs extends LogRepository<ItemLog> {}

    /** Collects what the framework gives up on. */
    static final class ReportedFailures implements DataErrorHandler {
        private final List<DataFailure> failures = new ArrayList<>();

        @Override public synchronized void onError(DataFailure failure) { failures.add(failure); }

        synchronized List<DataFailure> all() { return List.copyOf(failures); }
    }
}
