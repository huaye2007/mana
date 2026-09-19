package cn.managame.docdb.mongodb;

import cn.managame.annotation.*;
import cn.managame.core.metadata.EntityMetadata;
import com.mongodb.client.*;
import com.mongodb.client.model.IndexOptions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MongoIndexesTest {
    @Table(value = "bag", indexes = @Index(name = "uk_player_slot", columnList = "player_id, slot DESC", unique = true))
    public static class Bag {
        @Id public long id;
        @Column("player_id") @Field("owner") public long playerId;
        @Indexed(name = "idx_slot") public int slot;
    }
    @Test void compoundIndexesUseMappedDocumentNamesAndRetainSingleFieldIndexes() {
        var keys = new ArrayList<org.bson.Document>();
        var options = new ArrayList<IndexOptions>();
        var collection = proxy(MongoCollection.class, (p, m, a) -> {
            if (!m.getName().equals("createIndex")) throw new AssertionError(m);
            keys.add((org.bson.Document) a[0]); options.add((IndexOptions) a[1]); return options.getLast().getName();
        });
        var database = proxy(MongoDatabase.class, (p, m, a) -> switch (m.getName()) {
            case "getCollection" -> collection;
            case "listCollectionNames" -> proxy(ListCollectionNamesIterable.class, (i, method, args) -> {
                if (!method.getName().equals("iterator")) throw new AssertionError(method);
                var iterator = List.of("bag").iterator();
                return proxy(MongoCursor.class, (c, call, values) -> switch (call.getName()) {
                    case "hasNext" -> iterator.hasNext();
                    case "next" -> iterator.next();
                    case "close" -> null;
                    default -> throw new AssertionError(call);
                });
            });
            default -> throw new AssertionError(m);
        });
        var client = proxy(MongoClient.class, (p, m, a) -> switch (m.getName()) {
            case "getDatabase" -> database;
            case "close" -> null;
            default -> throw new AssertionError(m);
        });
        try (var access = new MongoDataAccess(client, "game")) {
            access.ensureSchema(EntityMetadata.inspect(Bag.class));
        }
        assertEquals(List.of("idx_slot", "uk_player_slot"), options.stream().map(IndexOptions::getName).toList());
        assertEquals(new org.bson.Document("slot", 1), keys.getFirst());
        assertEquals(List.of("owner", "slot"), new ArrayList<>(keys.get(1).keySet()));
        assertEquals(new org.bson.Document("owner", 1).append("slot", -1), keys.get(1));
        assertTrue(options.get(1).isUnique());
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
