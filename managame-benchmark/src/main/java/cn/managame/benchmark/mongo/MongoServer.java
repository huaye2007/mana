package cn.managame.benchmark.mongo;

import cn.managame.data.GameData;
import cn.managame.data.GroupRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.mongo.MongoEntityMapper;
import cn.managame.data.mongo.annotation.Collection;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;

/**
 * A real MongoDB for the mongo benchmarks, given by {@code MANAGAME_DATA_MONGO_URI}. Its database
 * {@code managame_bench} is dropped and made afresh for every trial.
 */
final class MongoServer implements AutoCloseable {
    @Collection(name = "player")
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

    /** A player's bag as one embedded document, as games keep a collection that changes often. */
    @Collection(name = "bag")
    public static class Bag {
        @Id long playerId;
        Map<Integer, Long> items = new ConcurrentHashMap<>();

        public Bag() {}

        Bag(long playerId) {
            this.playerId = playerId;
            for (int item = 0; item < BAG_ITEMS; item++) items.put(5000 + item, 1L);
        }
    }

    @Collection(name = "item")
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

    public static final class Bags extends SingleRepository<Long, Bag> {}

    public static final class Items extends GroupRepository<Integer, Item> {}

    static final int BAG_ITEMS = 50;

    final MongoClient client;
    final GameData data;

    /** The database made afresh, and a GameData over it that writes only on {@code flush()}, on the calling thread. */
    MongoServer() {
        String uri = System.getenv("MANAGAME_DATA_MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("set MANAGAME_DATA_MONGO_URI");
        client = MongoClients.create(uri);
        MongoDatabase database = client.getDatabase("managame_bench");
        database.drop();
        database.getCollection("item").createIndex(new Document("player_id", 1).append("slot", 1), new IndexOptions().unique(true));
        data = GameData.builder().errorHandler(failure -> {})
                .repositories(new MongoEntityMapper(database), List.of(Players.class, Bags.class, Items.class))
                .writeThreads(1).flushInterval(Duration.ofHours(1)).cacheExpire(Duration.ofHours(2)).build();
    }

    @Override public void close() {
        data.close();
        client.close();
    }
}
