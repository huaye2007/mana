package cn.managame.data.mongo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.annotation.Id;
import cn.managame.data.mongo.annotation.Collection;
import cn.managame.data.mongo.annotation.Field;
import cn.managame.data.spi.EntityType;
import com.mongodb.MongoClientSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonReader;
import org.bson.BsonString;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
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
    void readsAValueStoredAsAnotherTypeAsTheDriverDoes() {
        EntityType type = EntityType.of(Hero.class);
        mapper.prepare(type);
        // Written when the id was an int and the skill levels were ints: both fields are longs now.
        BsonDocument stored = new BsonDocument("_id", new BsonInt32(7)).append("skills", new BsonDocument("11", new BsonInt32(2)));
        Hero back = (Hero) mapper.decode(type, stored);
        assertEquals(7L, back.id);
        assertEquals(Map.of(11, 2L), back.skills);
        assertThrows(IllegalArgumentException.class, () -> mapper.decode(type, new BsonDocument("_id", new BsonString("seven"))));
    }

    /** A codec an application registered for a class the driver has one for: names are stored in capitals. */
    static final class CapitalNames implements Codec<String> {
        @Override public void encode(BsonWriter writer, String value, EncoderContext context) { writer.writeString(value.toUpperCase(Locale.ROOT)); }
        @Override public String decode(BsonReader reader, DecoderContext context) { return reader.readString().toLowerCase(Locale.ROOT); }
        @Override public Class<String> getEncoderClass() { return String.class; }
    }

    @Test
    void asksTheCodecAnApplicationRegisteredForAClass() {
        MongoEntityMapper ownCodec = new MongoEntityMapper(null, CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(new CapitalNames()), MongoClientSettings.getDefaultCodecRegistry()));
        EntityType type = EntityType.of(Hero.class);
        ownCodec.prepare(type);
        Hero hero = new Hero();
        hero.name = "kay";
        hero.titles.add("first");
        BsonDocument document = ownCodec.encode(type, hero);
        assertEquals("KAY", document.getString("nick").getValue());
        assertEquals("FIRST", document.getArray("titles").get(0).asString().getValue(), "an element of a collection too");
        Hero back = (Hero) ownCodec.decode(type, document);
        assertEquals("kay", back.name);
        assertEquals(List.of("first"), back.titles);
    }

    @Test
    void refusesAClassWithoutCollection() {
        assertThrows(IllegalArgumentException.class, () -> mapper.prepare(EntityType.of(Unnamed.class)));
    }
}
