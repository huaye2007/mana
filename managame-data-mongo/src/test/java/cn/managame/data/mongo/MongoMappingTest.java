package cn.managame.data.mongo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.EntityType;
import cn.managame.data.Id;
import com.mongodb.MongoClientSettings;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** How entities become documents and back, without a server. */
class MongoMappingTest {
    private final MongoEntityMapper mapper = new MongoEntityMapper(null, MongoClientSettings.getDefaultCodecRegistry());

    @Collection(name = "hero")
    static class Hero {
        @Id long id;
        @Field(name = "nick") String name;
        byte rank;
        short tier;
        float speed;
        char grade;
        byte[] avatar;
        Map<Integer, Long> skills = new ConcurrentHashMap<>();
        List<String> titles = new CopyOnWriteArrayList<>();
    }

    static class Unnamed {
        @Id long id;
    }

    @Test
    void mapsEveryFieldAndKeepsInitializedCollections() {
        EntityType type = EntityType.of(Hero.class);
        mapper.prepare(type);
        Hero hero = new Hero();
        hero.id = 7;
        hero.name = "kay";
        hero.rank = 3;
        hero.tier = 300;
        hero.speed = 1.5f;
        hero.grade = 'A';
        hero.avatar = new byte[] {1, 2};
        hero.skills.put(11, 2L);
        hero.titles.add("first");

        BsonDocument document = mapper.encode(type, hero);
        assertEquals(7L, document.getInt64("_id").getValue());
        assertEquals("kay", document.getString("nick").getValue());
        assertEquals(2L, document.getDocument("skills").getInt64("11").getValue());

        Hero back = (Hero) mapper.decode(type, document);
        assertEquals(7, back.id);
        assertEquals("kay", back.name);
        assertEquals(3, back.rank);
        assertEquals(300, back.tier);
        assertEquals(1.5f, back.speed);
        assertEquals('A', back.grade);
        assertArrayEquals(new byte[] {1, 2}, back.avatar);
        assertEquals(Map.of(11, 2L), back.skills);
        assertInstanceOf(ConcurrentHashMap.class, back.skills);
        assertEquals(List.of("first"), back.titles);
        assertInstanceOf(CopyOnWriteArrayList.class, back.titles);
    }

    @Test
    void refusesAClassWithoutCollection() {
        assertThrows(IllegalArgumentException.class, () -> mapper.prepare(EntityType.of(Unnamed.class)));
    }
}
