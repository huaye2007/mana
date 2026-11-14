package cn.managame.data.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.DataException;
import cn.managame.data.GameData;
import cn.managame.data.GroupKeys;
import cn.managame.data.GroupRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.mongo.annotation.Collection;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Against a real MongoDB, skipped unless its address is given ({@code MANAGAME_DATA_MONGO_URI=mongodb://127.0.0.1:27017}).
 * The test creates a database of its own and drops it.
 */
@EnabledIfEnvironmentVariable(named = "MANAGAME_DATA_MONGO_URI", matches = ".+")
class MongoIntegrationTest {
    @Collection(name = "player")
    static class Player {
        @Id long id;
        String name;
        int level;
        Map<Integer, Long> items = new ConcurrentHashMap<>();

        Player() {}

        Player(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static final class Players extends SingleRepository<Long, Player> {}

    @Test
    void writesToMongo() {
        try (MongoClient client = MongoClients.create(System.getenv("MANAGAME_DATA_MONGO_URI"))) {
            MongoDatabase database = client.getDatabase("managame_it_" + UUID.randomUUID().toString().replace("-", ""));
            try (GameData data = GameData.builder().errorHandler(failure -> {}).repositories(new MongoEntityMapper(database), List.of(Players.class))
                    .flushInterval(Duration.ofMinutes(1)).build()) {
                Players players = data.repository(Players.class);
                for (long id = 1; id <= 50; id++) players.insert(new Player(id, "p" + id));
                data.flush();
                players.update(7L, player -> player.items.put(5001, 3L));
                players.delete(8L);
                data.flush();
                players.evict(7L);
                assertEquals(Map.of(5001, 3L), players.get(7L).items);
                assertNull(players.get(8L));
                for (long id = 10; id <= 20; id++) players.evict(id);
                assertEquals(List.of(10L, 12L, 14L), List.copyOf(players.getAll(List.of(10L, 12L, 99L, 14L)).keySet()));
                assertEquals(49, database.getCollection("player").countDocuments());
                try (GameData preloaded = GameData.builder().errorHandler(failure -> {}).preloadedRepositories(new MongoEntityMapper(database), List.of(Players.class)).build()) {
                    assertEquals(49, preloaded.repository(Players.class).all().size());
                }
                database.getCollection("player").deleteOne(new Document("_id", 9L));
                players.update(9L, player -> player.level = 2);
                players.update(10L, player -> player.level = 2);
                assertThrows(DataException.class, data::flush, "an update of a document no longer stored fails, and is given up as the other is written");
                assertEquals(48, database.getCollection("player").countDocuments());
            } finally {
                database.drop();
            }
        }
    }

    @Collection(name = "item")
    static class Item {
        @Id long uid;
        @GroupKey long playerId;
        @MapKey int slot;

        Item() {}

        Item(long uid, long playerId, int slot) {
            this.uid = uid;
            this.playerId = playerId;
            this.slot = slot;
        }
    }

    static final class Items extends GroupRepository<Integer, Item> {}

    @Test
    void createsTheUniqueIndexOfAGroupedTypesCollection() {
        try (MongoClient client = MongoClients.create(System.getenv("MANAGAME_DATA_MONGO_URI"))) {
            MongoDatabase database = client.getDatabase("managame_it_" + UUID.randomUUID().toString().replace("-", ""));
            try {
                GameData.builder().errorHandler(failure -> {})
                        .repositories(MongoEntityMapper.builder(database).createMissing(false).build(), List.of(Items.class)).build().close();
                assertEquals(0, database.getCollection("item").listIndexes().into(new ArrayList<>()).size(), "told to create nothing");

                try (GameData data = GameData.builder().errorHandler(failure -> {}).repositories(new MongoEntityMapper(database), List.of(Items.class))
                        .flushInterval(Duration.ofMinutes(1)).build()) {
                    Items items = data.repository(Items.class);
                    items.insert(new Item(1, 100, 0));
                    items.insert(new Item(2, 100, 1));
                    data.flush();
                    items.evict(GroupKeys.of(100L));
                    assertEquals(2, items.getGroup(GroupKeys.of(100L)).size());
                }
                List<Document> indexes = database.getCollection("item").listIndexes().into(new ArrayList<>());
                Document unique = indexes.stream().filter(index -> Boolean.TRUE.equals(index.get("unique"))).findFirst().orElseThrow();
                assertEquals(List.of("player_id", "slot"), List.copyOf(unique.get("key", Document.class).keySet()));

                GameData.builder().errorHandler(failure -> {}).repositories(new MongoEntityMapper(database), List.of(Items.class)).build().close();
                assertEquals(indexes.size(), database.getCollection("item").listIndexes().into(new ArrayList<>()).size(), "made once");
            } finally {
                database.drop();
            }
        }
    }
}
