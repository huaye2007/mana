package cn.managame.data.mysql;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.data.DataException;
import cn.managame.data.GameData;
import cn.managame.data.GroupKeys;
import cn.managame.data.GroupRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.mysql.Fixtures.Database;
import cn.managame.data.mysql.Fixtures.Json;
import cn.managame.data.mysql.Fixtures.ReportedFailures;
import cn.managame.data.mysql.annotation.Column;
import cn.managame.data.mysql.annotation.Table;
import cn.managame.data.spi.EntityMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** What the entity mapper creates of its tables while GameData is built, and how a field is kept by a codec of the application's. */
class MysqlSchemaTest {
    private final Database database = Fixtures.database();

    @Table(name = "hero")
    static class Hero {
        @Id long id;
        String name;
        int level = 1;
        boolean active = true;
        double rate = 1.5;
        Map<Integer, Long> skills = new ConcurrentHashMap<>();   // a JSON column
        byte[] avatar;

        Hero() {}

        Hero(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Table(name = "pet")
    static class Pet {
        @Id long uid;
        @GroupKey long ownerId;
        @MapKey int slot;
        int kind;

        Pet() {}

        Pet(long uid, long ownerId, int slot) {
            this.uid = uid;
            this.ownerId = ownerId;
            this.slot = slot;
        }
    }

    static final class Heroes extends SingleRepository<Long, Hero> {}

    static final class Pets extends GroupRepository<Integer, Pet> {}

    private GameData build(EntityMapper mapper, Collection<Class<?>> repositories) {
        return GameData.builder().errorHandler(new ReportedFailures()).repositories(mapper, repositories)
                .flushInterval(Duration.ofMinutes(1)).cacheExpire(Duration.ofMinutes(2)).build();
    }

    @Test
    void createsTheTableOfAnEntityThatHasNone() {
        try (GameData data = build(new MysqlEntityMapper(database, new Json()), List.of(Heroes.class))) {
            Heroes heroes = data.repository(Heroes.class);
            Hero hero = new Hero(1, "kay");
            hero.skills.put(11, 2L);
            hero.avatar = new byte[] {1, 2};
            heroes.insert(hero);
            data.flush();
            heroes.evict(1L);

            Hero loaded = heroes.get(1L);
            assertEquals("kay", loaded.name);
            assertEquals(1, loaded.level);
            assertTrue(loaded.active);
            assertEquals(1.5, loaded.rate);
            assertEquals(Map.of(11, 2L), loaded.skills);
            assertArrayEquals(new byte[] {1, 2}, loaded.avatar);
        }
        assertEquals(List.of(List.of(1L, "kay", 1)), database.rows("SELECT id, name, level FROM hero"));
        assertThrows(IllegalStateException.class, () -> database.execute("INSERT INTO hero (id) VALUES (1)"), "the id is the primary key");
    }

    @Test
    void addsTheColumnsOfFieldsAddedSinceTheTableWasMade() {
        database.execute("CREATE TABLE hero (id BIGINT PRIMARY KEY, name VARCHAR(32))");
        database.execute("INSERT INTO hero VALUES (7, 'old')");
        try (GameData data = build(new MysqlEntityMapper(database, new Json()), List.of(Heroes.class))) {
            Hero stored = data.repository(Heroes.class).get(7L);
            assertEquals("old", stored.name);
            assertEquals(1, stored.level, "a column added for a primitive defaults to what the constructor gives the field");
            assertTrue(stored.active);
            assertEquals(1.5, stored.rate);
            assertInstanceOf(ConcurrentHashMap.class, stored.skills, "a column that is NULL leaves the field as the constructor made it");
            assertNull(stored.avatar);
        }
        assertEquals(List.of(List.of(7L, "old", 1)), database.rows("SELECT id, name, level FROM hero"));
    }

    @Test
    void createsAGroupedTypesTableWithAUniqueKeyOnItsGroupAndMapKey() {
        try (GameData data = build(new MysqlEntityMapper(database), List.of(Pets.class))) {
            Pets pets = data.repository(Pets.class);
            pets.insert(new Pet(1, 100, 0));
            pets.insert(new Pet(2, 100, 1));
            data.flush();
            pets.evict(GroupKeys.of(100L));
            assertEquals(2, pets.getGroup(GroupKeys.of(100L)).size());
        }
        assertThrows(IllegalStateException.class, () -> database.execute("INSERT INTO pet (uid, owner_id, slot, kind) VALUES (3, 100, 0, 0)"),
                "two rows of a group at one map key");
    }

    @Test
    void addsTheUniqueKeyToATableWithNoIndexOnItsGroup() {
        database.execute("CREATE TABLE pet (uid BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, slot INT NOT NULL, kind INT NOT NULL)");
        build(new MysqlEntityMapper(database), List.of(Pets.class)).close();
        database.execute("INSERT INTO pet VALUES (1, 100, 0, 0)");
        assertThrows(IllegalStateException.class, () -> database.execute("INSERT INTO pet VALUES (2, 100, 0, 0)"));
    }

    @Test
    void leavesATableThatHasAnIndexOnItsGroupAsItIs() {
        database.execute("CREATE TABLE pet (uid BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, slot INT NOT NULL, kind INT NOT NULL)");
        database.execute("CREATE INDEX pet_owner ON pet (owner_id)");
        build(new MysqlEntityMapper(database), List.of(Pets.class)).close();
        database.execute("INSERT INTO pet VALUES (1, 100, 0, 0)");
        database.execute("INSERT INTO pet VALUES (2, 100, 0, 0)");   // the application's index is no unique one, and none was added
        assertEquals(2, database.rows("SELECT uid FROM pet").size());
    }

    @Test
    void createsNothingWhenToldToLeaveTheTablesAlone() {
        MysqlEntityMapper checksOnly = MysqlEntityMapper.builder(database).json(new Json()).createMissing(false).build();
        assertEquals(DATA_LOAD_FAILED, assertThrows(DataException.class, () -> build(checksOnly, List.of(Heroes.class))).errorCode(), "no table");
        assertThrows(IllegalStateException.class, () -> database.rows("SELECT id FROM hero"));

        database.execute("CREATE TABLE hero (id BIGINT PRIMARY KEY, name VARCHAR(32))");
        assertEquals(DATA_LOAD_FAILED, assertThrows(DataException.class, () -> build(checksOnly, List.of(Heroes.class))).errorCode(), "no column");
        assertThrows(IllegalStateException.class, () -> database.rows("SELECT level FROM hero"));
    }

    enum Grade { NOVICE, MASTER }

    @Table(name = "knight")
    static class Knight {
        @Id long id;
        Grade grade = Grade.NOVICE;
        Instant joined;
        @Column(type = "VARCHAR(32)", defaultValue = "'nobody'") String title;
        @Column(name = "pts", defaultValue = "7") Integer points;

        Knight() {}

        Knight(long id) { this.id = id; }
    }

    static final class Knights extends SingleRepository<Long, Knight> {}

    /** An enum kept as its name: the application's own way to keep a field. */
    record EnumAsName(Class<? extends Enum<?>> type) implements ColumnCodec {
        @Override public String columnType() { return "VARCHAR(32)"; }

        @Override public Object encode(Object value) { return ((Enum<?>) value).name(); }

        @Override @SuppressWarnings({"unchecked", "rawtypes"}) public Object decode(ResultSet resultSet, int index) throws SQLException {
            String name = resultSet.getString(index);
            return name == null ? null : Enum.valueOf((Class) type, name);
        }
    }

    /** An Instant kept as its milliseconds. */
    static final class InstantAsMillis implements ColumnCodec {
        @Override public String columnType() { return "BIGINT"; }

        @Override public Object encode(Object value) { return ((Instant) value).toEpochMilli(); }

        @Override public Object decode(ResultSet resultSet, int index) throws SQLException {
            long millis = resultSet.getLong(index);
            return resultSet.wasNull() ? null : Instant.ofEpochMilli(millis);
        }
    }

    @SuppressWarnings("unchecked")
    private MysqlEntityMapper withCodecs() {
        return MysqlEntityMapper.builder(database).columnCodecs(field -> field.type().isEnum() ? new EnumAsName((Class<? extends Enum<?>>) field.type())
                : field.type() == Instant.class ? new InstantAsMillis() : null).build();
    }

    @Test
    void keepsAFieldAsTheApplicationsCodecForItSays() {
        try (GameData data = build(withCodecs(), List.of(Knights.class))) {
            Knights knights = data.repository(Knights.class);
            Knight knight = new Knight(1);
            knight.grade = Grade.MASTER;
            knight.joined = Instant.ofEpochMilli(1234);
            knights.insert(knight);
            data.flush();
            knights.evict(1L);

            Knight loaded = knights.get(1L);
            assertEquals(Grade.MASTER, loaded.grade);
            assertEquals(Instant.ofEpochMilli(1234), loaded.joined);
        }
        assertEquals(List.of(List.of("MASTER", 1234L)), database.rows("SELECT grade, joined FROM knight"), "as the codecs wrote them, in the columns they named");
    }

    @Test
    void createsAColumnAsItsAnnotationSays() {
        database.execute("CREATE TABLE knight (id BIGINT PRIMARY KEY)");
        database.execute("INSERT INTO knight VALUES (1)");
        try (GameData data = build(withCodecs(), List.of(Knights.class))) {
            Knight stored = data.repository(Knights.class).get(1L);
            assertEquals("nobody", stored.title, "the DEFAULT of the annotation, in a row stored before the column was added");
            assertEquals(7, stored.points);
            assertEquals(Grade.NOVICE, stored.grade, "no DEFAULT for a field that is no primitive: NULL, so as the constructor made it");
            assertNull(stored.joined);
        }
        assertEquals(List.of(List.of("nobody", 7)), database.rows("SELECT title, pts FROM knight"));
    }
}
