package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.Fixtures.Item;
import cn.managame.data.Fixtures.Items;
import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import cn.managame.data.spi.Change;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Repositories registered with preloadedRepositories: the whole table in memory from build on, never asked again. */
class PreloadTest {
    private final MemoryMapper database = new MemoryMapper();
    private GameData data;
    private Players players;
    private Items items;

    /** Rows a server that ran before this one left: players 1 and 2, the items of player 1. */
    @BeforeEach
    void start() {
        try (GameData earlier = GameData.builder().errorHandler(new ReportedFailures()).repositories(database, List.of(Players.class, Items.class)).build()) {
            earlier.repository(Players.class).insert(new Player(1, "ann"));
            earlier.repository(Players.class).insert(new Player(2, "bob"));
            earlier.repository(Items.class).insert(new Item(11, 1, 0, 5001, 1));
            earlier.repository(Items.class).insert(new Item(12, 1, 1, 5002, 1));
        }
        data = GameData.builder().errorHandler(new ReportedFailures()).preloadedRepositories(database, List.of(Players.class, Items.class))
                .flushInterval(Duration.ofMinutes(1)).build();
        players = data.repository(Players.class);
        items = data.repository(Items.class);
    }

    @AfterEach
    void close() { data.close(); }

    private Set<Long> playerIds() { return players.all().stream().map(player -> player.id).collect(Collectors.toSet()); }

    @Test
    void loadsTheWholeTableAtBuildAndNeverAsksAgain() {
        assertEquals(2, database.tableLoads.get());
        int askedBefore = database.loads.get() + database.bulkLoads.get();   // by the server that ran before
        assertEquals("ann", players.get(1L).name);
        assertNull(players.get(3L));
        assertEquals(Set.of(1L), players.getAll(List.of(1L, 3L)).keySet());
        assertEquals(2, items.getGroup(GroupKeys.of(1L)).size());
        assertEquals(0, items.getGroup(GroupKeys.of(2L)).size());
        assertEquals(askedBefore, database.loads.get() + database.bulkLoads.get(), "the database is never asked");
        assertEquals(2, data.stats().entities().getFirst().cached(), "ids that do not exist leave nothing in memory");
    }

    @Test
    void listsEverythingAndChangesAsUsual() {
        assertEquals(Set.of(1L, 2L), playerIds());
        players.insert(new Player(3, "cid"));
        players.delete(1L);
        assertEquals(Set.of(2L, 3L), playerIds());
        assertThrows(IllegalStateException.class, () -> players.update(new Player(9, "who")), "an id not in memory does not exist");
        players.delete(9L);

        items.insert(new Item(21, 2, 0, 5001, 1));
        assertEquals(Set.of(GroupKeys.of(1L), GroupKeys.of(2L)), items.all().keySet());
        items.deleteGroup(GroupKeys.of(1L));
        assertEquals(Set.of(GroupKeys.of(2L)), items.all().keySet());

        data.flush();
        assertEquals(List.of(2L, 3L), database.rows(Player.class).stream().map(row -> row.get("id")).toList());
        assertEquals(List.of(21L), database.rows(Item.class).stream().map(row -> row.get("uid")).toList());
    }

    @Test
    void keepsEverythingInMemory() {
        assertThrows(IllegalStateException.class, () -> players.evict(1L));
        assertThrows(IllegalStateException.class, () -> items.evict(GroupKeys.of(1L)));
        data.close();
        data = GameData.builder().errorHandler(new ReportedFailures()).repositories(database, List.of(Players.class)).flushInterval(Duration.ofMinutes(1)).build();
        assertThrows(IllegalStateException.class, () -> data.repository(Players.class).all(), "memory holds only some");
    }

    @Test
    void aMapperThatCannotLoadATableFailsTheBuild() {
        EntityMapper noTables = new EntityMapper() {
            @Override public void prepare(EntityType type) {}

            @Override public Object load(EntityType type, Object id) { return null; }

            @Override public List<Object> loadGroup(EntityType type, GroupKeys key) { return List.of(); }

            @Override public void write(EntityType type, DataOperation operation, List<? extends Change> batch) {}
        };
        DataException failure = assertThrows(DataException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).preloadedRepositories(noTables, Players.class).build());
        assertEquals(DATA_LOAD_FAILED, failure.errorCode());
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
