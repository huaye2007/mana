package cn.managame.benchmark.data;

import cn.managame.data.DataOperation;
import cn.managame.data.GameData;
import cn.managame.data.GroupKeys;
import cn.managame.data.GroupRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.spi.Change;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.time.Duration;
import java.util.ArrayList;
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

    public static class Guild {
        @Id long id;
        String name;
        long leaderId;
        int level;

        public Guild() {}

        Guild(long id) {
            this.id = id;
            name = "guild" + id;
            level = 1;
        }
    }

    public static final class Players extends SingleRepository<Long, Player> {}

    /** Preloaded: every guild in memory from the start. */
    public static final class Guilds extends SingleRepository<Long, Guild> {}

    public static final class Items extends GroupRepository<Integer, Item> {}

    static final int GUILDS = 1024;

    /** Loads nothing and reads every field of what it writes, as a mapper encoding rows does, then forgets it. */
    static final class DiscardingMapper implements EntityMapper {
        final LongAdder fieldsRead = new LongAdder();

        @Override public void prepare(EntityType type) {}

        @Override public Object load(EntityType type, Object id) { return null; }

        @Override public List<Object> loadGroup(EntityType type, GroupKeys key) { return List.of(); }

        /** The guilds, 0 to {@value #GUILDS} - 1; no other table. */
        @Override public List<Object> loadTable(EntityType type) {
            List<Object> guilds = new ArrayList<>(GUILDS);
            if (type.entityClass() == Guild.class) for (long id = 0; id < GUILDS; id++) guilds.add(new Guild(id));
            return guilds;
        }

        @Override public void write(EntityType type, DataOperation operation, List<? extends Change> batch) {
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
        DiscardingMapper mapper = new DiscardingMapper();
        data = GameData.builder().errorHandler(failure -> {}).repositories(mapper, List.of(Players.class, Items.class))
                .preloadedRepositories(mapper, List.of(Guilds.class)).writeThreads(1)
                .flushInterval(Duration.ofHours(1)).cacheExpire(Duration.ofHours(2)).build();
    }

    Guilds guilds() { return data.repository(Guilds.class); }

    Players players() { return data.repository(Players.class); }

    Items items() { return data.repository(Items.class); }

    @Override public void close() { data.close(); }
}
