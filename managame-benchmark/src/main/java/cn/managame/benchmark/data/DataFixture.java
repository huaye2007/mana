package cn.managame.benchmark.data;

import cn.managame.data.Change;
import cn.managame.data.DataOperation;
import cn.managame.data.EntityMapper;
import cn.managame.data.EntityType;
import cn.managame.data.GameData;
import cn.managame.data.GroupKey;
import cn.managame.data.GroupKeys;
import cn.managame.data.GroupRepository;
import cn.managame.data.Id;
import cn.managame.data.MapKey;
import cn.managame.data.SingleRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * A player of ten scalar fields and a bag of items, kept by a mapper that reads every field of what it writes and
 * keeps nothing, so that the data benchmarks measure the component alone and no database.
 */
final class DataFixture implements AutoCloseable {
    public static class Player {
        @Id long id;
        String name;
        int level;
        long exp;
        long gold;
        int diamond;
        int vip;
        long lastLogin;
        long power;
        long guildId;

        public Player() {}

        Player(long id) {
            this.id = id;
            name = "player" + id;
            level = 1;
        }
    }

    public static class Item {
        @Id long uid;
        @GroupKey long playerId;
        @MapKey int slot;
        int configId;
        int amount;

        public Item() {}

        Item(long uid, long playerId, int slot) {
            this.uid = uid;
            this.playerId = playerId;
            this.slot = slot;
            configId = 5000 + slot;
            amount = 1;
        }
    }

    public static final class Players extends SingleRepository<Long, Player> {}

    public static final class Items extends GroupRepository<Integer, Item> {}

    /** Loads nothing and reads every field of what it writes, as a mapper encoding rows does, then forgets it. */
    static final class DiscardingMapper implements EntityMapper {
        final LongAdder fieldsRead = new LongAdder();

        @Override public void prepare(EntityType type) {}

        @Override public Object load(EntityType type, Object id) { return null; }

        @Override public List<Object> loadGroup(EntityType type, GroupKeys key) { return List.of(); }

        @Override public void write(EntityType type, DataOperation operation, List<Change> batch) {
            int read = 0;
            for (Change change : batch) {
                if (change.entity() == null) continue;
                for (var field : type.fields()) if (field.get(change.entity()) != null) read++;
            }
            fieldsRead.add(read);
        }
    }

    final GameData data;

    /** A long flush interval leaves writing to the benchmark's own {@code flush()}, or out of it. */
    DataFixture() {
        data = GameData.builder().repositories(new DiscardingMapper(), List.of(Players.class, Items.class)).writeThreads(1)
                .flushInterval(Duration.ofHours(1)).cacheExpire(Duration.ofHours(2)).build();
    }

    Players players() { return data.repository(Players.class); }

    Items items() { return data.repository(Items.class); }

    @Override public void close() { data.close(); }
}
