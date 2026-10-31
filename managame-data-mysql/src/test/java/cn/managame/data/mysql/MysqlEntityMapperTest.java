package cn.managame.data.mysql;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.DataException;
import cn.managame.data.DataOperation;
import cn.managame.data.GameData;
import cn.managame.data.GroupKeys;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.Id;
import cn.managame.data.mysql.Fixtures.Database;
import cn.managame.data.mysql.Fixtures.Item;
import cn.managame.data.mysql.Fixtures.Items;
import cn.managame.data.mysql.Fixtures.Json;
import cn.managame.data.mysql.Fixtures.Player;
import cn.managame.data.mysql.Fixtures.Players;
import cn.managame.data.mysql.Fixtures.ReportedFailures;
import cn.managame.data.mysql.annotation.Table;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MysqlEntityMapperTest {
    private final Database database = Fixtures.database();
    private final ReportedFailures reported = new ReportedFailures();
    private final GameData data = GameData.builder()
            .repositories(new MysqlEntityMapper(database, new Json()), List.of(Players.class, Items.class))
            .errorHandler(reported).flushInterval(Duration.ofMinutes(1)).cacheExpire(Duration.ofMinutes(2)).shutdownTimeout(Duration.ZERO).build();
    private final Players players = data.repository(Players.class);
    private final Items items = data.repository(Items.class);

    @AfterEach
    void close() {
        database.refuseConnections = false;
        try {
            data.close();
        } catch (DataException e) {
            // a test that leaves changes unwritten on purpose
        }
    }

    private List<List<Object>> players() { return database.rows("SELECT id, name, level, gold, items FROM player ORDER BY id"); }

    private List<List<Object>> items() { return database.rows("SELECT uid, player_id, slot, config_id, amount FROM item ORDER BY uid"); }

    @Test
    void writesRowsWithJsonColumns() {
        Player player = new Player(1, "ann");
        player.items.put(1001, 5L);
        players.insert(player);
        data.flush();
        assertEquals(List.of(List.of(1L, "ann", 1, 0L, "{\"1001\":5}")), players());

        player.level = 2;
        player.gold = 100;
        players.update(player);
        players.insert(new Player(2, "bob"));
        data.flush();
        assertEquals(List.of(List.of(1L, "ann", 2, 100L, "{\"1001\":5}"), List.of(2L, "bob", 1, 0L, "{}")), players());

        players.delete(1L);
        data.flush();
        assertEquals(1, players().size());
    }

    @Test
    void loadsJsonIntoTheCollectionTheFieldIsInitializedWith() {
        database.execute("INSERT INTO player VALUES (7, 'bob', 3, 50, '{\"1001\":2}')");
        Player player = players.get(7L);
        assertEquals(3, player.level);
        assertEquals(2L, player.items.get(1001));
        assertInstanceOf(ConcurrentHashMap.class, player.items);
    }

    @Test
    void updatesManyRowsInFewStatements() {
        for (long id = 1; id <= 250; id++) players.insert(new Player(id, "p" + id));
        data.flush();
        for (long id = 1; id <= 250; id++) players.update(id, player -> player.level = (int) player.id);
        data.flush();
        assertEquals(List.of(List.of(250)), database.rows("SELECT level FROM player WHERE id = 250"));
        assertEquals(List.of(List.of(250L)), database.rows("SELECT COUNT(*) FROM player WHERE level = id"));
    }

    @Test
    void loadsGroupsAndDeletesBeforeInsertsSoAUniqueSlotCanBeReused() {
        database.execute("INSERT INTO item VALUES (1, 100, 0, 5001, 1), (2, 100, 1, 5002, 3), (3, 200, 0, 5001, 9)");
        assertEquals(2, items.getGroup(GroupKeys.of(100L)).size());
        items.delete(items.get(GroupKeys.of(100L), 0));
        items.insert(new Item(4, 100, 0, 5004, 1));   // the same (player_id, slot), unique in the table
        data.flush();
        assertEquals(List.of(List.of(2L, 100L, 1, 5002, 3), List.of(3L, 200L, 0, 5001, 9), List.of(4L, 100L, 0, 5004, 1)), items());
    }

    @Test
    void dropsARowItsDataBreaksAndWritesTheOthers() {
        players.insert(new Player(1, "ann"));
        Player longName = new Player(2, "a name longer than the column");
        players.insert(longName);
        players.insert(new Player(3, "cid"));
        assertThrows(DataException.class, data::flush);
        assertEquals(List.of(1L, 3L), players().stream().map(row -> row.getFirst()).toList());
        assertSame(longName, reported.all().getFirst().value());
        assertInstanceOf(SQLException.class, reported.all().getFirst().cause());
    }

    @Test
    void keepsChangesWhileTheDatabaseRefusesConnections() {
        players.insert(new Player(1, "ann"));
        database.refuseConnections = true;
        assertThrows(DataException.class, data::flush);
        database.refuseConnections = false;
        data.flush();
        assertEquals(1, players().size());
        assertEquals(List.of(), reported.all());
    }

    @Test
    void writesAgainAnInsertWhoseCommitWasNotAcknowledged() {
        players.insert(new Player(1, "ann"));
        database.failAfterNextCommit = true;
        assertThrows(DataException.class, data::flush);
        data.flush();   // a delete and insert in one transaction, so the stored row is no duplicate
        assertEquals(1, players().size());
        assertEquals(List.of(), reported.all());
    }

    @Test
    void reportsAnUpdateOfARowNoLongerStored() {
        database.execute("INSERT INTO player VALUES (1, 'ann', 1, 0, NULL)");
        Player player = players.get(1L);
        database.execute("DELETE FROM player");
        player.level = 3;
        players.update(player);
        assertThrows(DataException.class, data::flush);
        assertEquals(DataOperation.UPDATE, reported.all().getFirst().operation());
    }

    @Table(name = "player")
    static class WithMissingColumn {
        @Id long id;
        long missing;
    }

    static class WithoutTable {
        @Id long id;
    }

    @Table(name = "player")
    static class WithJson {
        @Id long id;
        List<String> titles;
    }

    static final class MissingColumns extends SingleRepository<Long, WithMissingColumn> {}

    static final class WithoutTables extends SingleRepository<Long, WithoutTable> {}

    static final class WithJsons extends SingleRepository<Long, WithJson> {}

    @Test
    void refusesAMappingThatDoesNotFitAtBuild() {
        MysqlEntityMapper withoutCodec = new MysqlEntityMapper(database);
        assertEquals(DATA_LOAD_FAILED, assertThrows(DataException.class,
                () -> GameData.builder().repositories(withoutCodec, MissingColumns.class).build()).errorCode());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(withoutCodec, WithoutTables.class).build());
        assertThrows(IllegalStateException.class, () -> GameData.builder().repositories(withoutCodec, WithJsons.class).build());
    }
}
