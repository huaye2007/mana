package cn.managame.core.repository;

import cn.managame.core.DataException;
import cn.managame.core.GameData;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.support.DataTestSupport.*;

@Timeout(15)
class RepositoryFactoryTest {
    public interface Rows extends SingleRepository<Row, Long> {
        default boolean contains(long id) { return get(id).isPresent(); }
        default String label() { return "rows"; }
        default void add(Row row) { insert(row); }
    }
    public interface AliasRows extends SingleRepository<Row, Long> { }
    public interface Base<T> extends SingleRepository<T, Long> { }
    public interface GenericRows extends Base<Row> { }
    public interface Groups extends GroupRepository<Row, Long> { }
    public interface AliasGroups extends GroupRepository<Row, Long> { }
    public interface Both extends SingleRepository<Row, Long>, GroupRepository<Row, Long> { }
    public interface Unsupported extends Rows { List<Row> findByValue(int value); }
    public interface WrongOverload extends Rows { void update(String value); }
    @SuppressWarnings("rawtypes") public interface Raw extends SingleRepository { }
    public interface Unresolved<T> extends SingleRepository<T, Long> { }
    public interface Redeclared extends Rows { @Override Optional<Row> get(Long id); }

    public interface WrongSingleId extends SingleRepository<Row, String> { }
    public interface WrongGroupId extends GroupRepository<Row, Integer> { }
    public interface GroupBase<T, ID> extends GroupRepository<T, ID> { }
    public interface GenericGroups extends GroupBase<Row, Long> { }
    public static class TextIdRow {
        @cn.managame.annotation.Id public String id;
        @cn.managame.annotation.GroupKey public int owner;
        @cn.managame.annotation.MapKey public UUID slot;
    }
    public interface TextGroups extends GroupRepository<TextIdRow, String> { }

    @Test void idMismatchFailsBeforeSchemaOrCacheInitializationIncludingAliases() {
        var access = new Access();
        try (var data = game(access)) {
            for (Class<?> type : List.of(WrongSingleId.class, WrongGroupId.class)) {
                var error = assertThrows(DataException.class, () -> data.repository(type));
                assertTrue(error.getMessage().contains("ID type must match @Id"));
            }
            assertEquals(0, access.schemaCalls.get());
            data.repository(Rows.class);
            assertThrows(DataException.class, () -> data.repository(WrongSingleId.class));
            assertEquals(1, access.schemaCalls.get());
        }
    }

    @Test void groupIdTypeIsIndependentOfAnnotationDerivedGroupAndMapKeys() {
        try (var data = game(new Access())) {
            var groups = data.repository(TextGroups.class);
            var map = groups.getGroup(7);
            var row = new TextIdRow(); row.id = "row-1"; row.owner = 7; row.slot = UUID.randomUUID();
            groups.insert(row);
            assertSame(row, map.get(row.slot));
            assertInstanceOf(UUID.class, map.keySet().iterator().next());
            assertSame(map, groups.getGroup(7));
            assertThrows(DataException.class, () -> groups.getGroup(7L));
            assertThrows(DataException.class, () -> groups.deleteGroup("7"));
            groups.deleteGroup(7);
            assertTrue(map.isEmpty());
        }
    }

    @Test void inheritedGroupIdTypeResolvesAndSharesTheSameCachedMap() {
        try (var data = game(new Access())) {
            var groups = data.repository(GenericGroups.class);
            var map = groups.getGroup(1L);
            var row = new Row(3); groups.insert(row);
            assertSame(row, map.get(3L));
            assertSame(map, data.repository(Groups.class).getGroup(1L));
        }
    }

    static GameData game(Access access) { return GameData.builder().dataAccess("game", access).build(); }

    @Test void interfaceCreatesTypedSingletonWithDefaultMethodsAndGenericInheritance() {
        var access = new Access();
        try (var game = game(access)) {
            Rows rows = game.repository(Rows.class);
            assertSame(rows, game.repository(Rows.class));
            var row = new Row(1); rows.insert(row);
            assertTrue(rows.contains(1));
            assertEquals("rows", rows.label());
            rows.add(new Row(2)); assertTrue(rows.contains(2));
            assertSame(row, game.repository(GenericRows.class).get(1L).orElseThrow());
            assertSame(row, game.repository(Redeclared.class).get(1L).orElseThrow());
            assertEquals(1, access.schemaCalls.get(), "one shared delegate per entity");
            assertEquals(rows, rows);
            assertNotEquals(rows, game.repository(AliasRows.class));
            assertTrue(rows.toString().contains("Rows"));
            game.repository(AliasRows.class).deleteById(1L);
            assertFalse(rows.contains(1));
        }
    }

    @Test void invalidInterfacesFailDuringInitialization() {
        try (var game = game(new Access())) {
            assertThrows(DataException.class, () -> game.repository(Row.class));
            assertThrows(DataException.class, () -> game.repository(Raw.class));
            assertThrows(DataException.class, () -> game.repository(Unresolved.class));
            assertThrows(DataException.class, () -> game.repository(Both.class));
            assertThrows(DataException.class, () -> game.repository(Unsupported.class));
            assertThrows(DataException.class, () -> game.repository(WrongOverload.class));
        }
    }

    @Test void sameEntityCannotMixSingleAndGroupModesButDifferentAccessesCan() {
        try (var game = GameData.builder().dataAccess("one", new Access()).dataAccess("two", new Access()).build()) {
            game.repository("one", Rows.class);
            assertThrows(DataException.class, () -> game.repository("one", Groups.class));
            game.repository("two", Groups.class);
            assertThrows(DataException.class, () -> game.repository("two", Rows.class));
        }
    }

    @Test void concurrentLookupPublishesOnlyOneInstanceAndOneSchemaInitialization() {
        var access = new Access();
        try (var game = game(access)) {
            var results = new CopyOnWriteArrayList<Rows>();
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Task>();
            for (int i = 0; i < 16; i++) tasks.add(task(() -> { await(start); results.add(game.repository(Rows.class)); }));
            start.countDown(); tasks.forEach(Task::join);
            assertEquals(16, results.size());
            for (Rows rows : results) assertSame(results.getFirst(), rows);
            assertEquals(1, access.schemaCalls.get());
        }
    }

    @Test void closedGameRejectsRepositoryInitialization() {
        var game = game(new Access()); game.close();
        assertThrows(IllegalStateException.class, () -> game.repository(Rows.class));
    }
}
