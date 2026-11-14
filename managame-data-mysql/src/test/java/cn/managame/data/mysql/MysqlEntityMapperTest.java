package cn.managame.data.mysql;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.data.DataException;
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
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
    void getsMoreIdsThanOneSelectNames() {
        for (long id = 1; id <= 1500; id++) players.insert(new Player(id, "p" + id));
        data.flush();
        for (long id = 1; id <= 1500; id++) players.evict(id);
        List<Long> asked = new ArrayList<>();
        for (long id = 1600; id >= 1; id--) asked.add(id);
        Map<Long, Player> found = players.getAll(asked);
        assertEquals(1500, found.size(), "two SELECTs, without the ids above 1500");
        assertEquals(1500L, found.keySet().iterator().next());
        assertEquals("p2", found.get(2L).name);
    }

    @Test
    void preloadsAWholeTable() {
        database.execute("INSERT INTO player VALUES (7, 'bob', 3, 50, '{\"1001\":2}')");
        database.execute("INSERT INTO player VALUES (8, 'cid', 1, 0, '{}')");
        try (GameData preloaded = GameData.builder().errorHandler(new ReportedFailures()).preloadedRepositories(new MysqlEntityMapper(database, new Json()), List.of(Players.class)).build()) {
            Players all = preloaded.repository(Players.class);
            assertEquals(Set.of(7L, 8L), all.all().stream().map(player -> player.id).collect(Collectors.toSet()));
            assertEquals(2L, all.get(7L).items.get(1001));
        }
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
    void updatesManyRowsInOneBatch() {
        for (long id = 1; id <= 250; id++) players.insert(new Player(id, "p" + id));
        data.flush();
        for (long id = 1; id <= 250; id++) players.update(id, player -> player.level = (int) player.id);
        data.flush();
        assertEquals(List.of(List.of(250)), database.rows("SELECT level FROM player WHERE id = 250"));
        assertEquals(List.of(List.of(250L)), database.rows("SELECT COUNT(*) FROM player WHERE level = id"));
    }

    @Test
    void readsAndDeletesAnyNumberOfRowsWithTheStatementsBuiltForTheTable() {
        StringBuilder values = new StringBuilder();
        for (long id = 1; id <= 250; id++) values.append(id == 1 ? "" : ", ").append("(").append(id).append(", 'p', 1, 0, '{}')");
        database.execute("INSERT INTO player VALUES " + values);
        assertEquals(3, players.getAll(List.of(1L, 2L, 2L, 3L)).size(), "a repeated id once");
        List<Long> rest = new ArrayList<>();
        for (long id = 4; id <= 250; id++) rest.add(id);
        assertEquals(247, players.getAll(rest).size(), "three hundreds, the last filled by repeating an id");
        for (long id = 1; id <= 120; id++) players.delete(id);
        data.flush();
        assertEquals(List.of(List.of(130L)), database.rows("SELECT COUNT(*) FROM player"));
        Set<String> batchReads = database.prepared.stream().filter(sql -> sql.contains(" IN (")).collect(Collectors.toSet());
        assertEquals(1, batchReads.size(), "one batch SELECT whatever the number of ids: " + batchReads);
        assertEquals(1, database.prepared.stream().filter(sql -> sql.startsWith("DELETE")).count(), "one DELETE, batched");
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
    void writesItemsThatTradeSlotsWhereverABatchEnds() {
        database.execute("INSERT INTO item VALUES (1, 100, 0, 5001, 1), (2, 100, 1, 5002, 1), (3, 100, 2, 5003, 1)");
        try (GameData twoABatch = GameData.builder().repositories(new MysqlEntityMapper(database, new Json()), List.of(Items.class))
                .errorHandler(reported).flushInterval(Duration.ofMinutes(1)).batchSize(2).build()) {
            Items bags = twoABatch.repository(Items.class);
            Map<Integer, Item> bag = bags.getGroup(GroupKeys.of(100L));
            Item first = bag.get(0), second = bag.get(1), third = bag.get(2);
            for (Item item : List.of(first, second, third)) bags.delete(item);
            first.slot = 1;
            second.slot = 2;
            third.slot = 0;
            for (Item item : List.of(first, second, third)) bags.insert(item);
            twoABatch.flush();   // unique (player_id, slot): none can take its slot before the others have left theirs
        }
        assertEquals(List.of(List.of(1L, 1), List.of(2L, 2), List.of(3L, 0)), database.rows("SELECT uid, slot FROM item ORDER BY uid"));
        assertEquals(List.of(), reported.all());
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
    void keepsChangesWhileTheServerCannotWrite() {
        players.insert(new Player(1, "ann"));
        players.insert(new Player(2, "bob"));
        // Read-only after a failover, read-only again, the disk full, the query killed: none says anything about the rows.
        for (int error : List.of(1290, 1836, 1021, 1317)) {
            database.serverError = error;
            assertThrows(DataException.class, data::flush);
            assertEquals(List.of(), reported.all(), "MySQL error " + error + " is no reason to give a row up");
        }
        database.serverError = 0;
        data.flush();
        assertEquals(2, players().size());
        assertEquals(List.of(), reported.all());
    }

    @Test
    void keepsEveryRowTheServerRefusesForAReasonTheMapperDoesNotKnow() {
        players.insert(new Player(1, "ann"));
        players.insert(new Player(2, "bob"));
        database.serverError = 1142;   // the user's right to write taken away: an error the mapper has no rule for
        assertThrows(DataException.class, data::flush);
        assertThrows(DataException.class, data::flush);
        assertEquals(List.of(), reported.all(), "no row of the batch could be written, so none is given up");
        database.serverError = 0;
        data.flush();
        assertEquals(2, players().size());
        assertEquals(List.of(), reported.all());
    }

    @Test
    void tellsWhatTheServerCannotDoNowFromWhatTheDataBreaks() {
        MysqlEntityMapper mapper = new MysqlEntityMapper(database);
        for (int error : List.of(1021, 1037, 1038, 1041, 1114, 1203, 1226, 1290, 1317, 1792, 1836))
            assertTrue(mapper.isRetryable(new SQLException("the server cannot now", "HY000", error)), "MySQL error " + error);
        BatchUpdateException inABatch = new BatchUpdateException("batch", new int[0]);
        inABatch.setNextException(new SQLException("read-only", "HY000", 1290));
        assertTrue(mapper.isRetryable(inABatch), "as a batch's next exception");
        assertTrue(mapper.isRetryable(new IllegalStateException(new SQLException("read-only", "HY000", 1290))), "as a cause");
        assertTrue(mapper.isRetryable(new SQLTransientConnectionException("no connection")), "what every mapper tries again");
        assertTrue(mapper.isRetryable(new SQLException("lock wait", "HY000", 1205)));
        assertFalse(mapper.isRetryable(new SQLException("Data too long for column", "22001", 1406)));
        assertFalse(mapper.isRetryable(new SQLException("Duplicate entry", "23000", 1062)));
        assertFalse(mapper.isRetryable(new IllegalArgumentException("cannot encode")));
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
    void writesAgainAnUpdatedRowNoLongerStored() {
        database.execute("INSERT INTO player VALUES (1, 'ann', 1, 0, NULL)");
        Player player = players.get(1L);
        database.execute("DELETE FROM player");
        player.level = 3;
        players.update(player);
        data.flush();
        assertEquals(List.of(1L, "ann", 3, 0L), players().getFirst().subList(0, 4));
        assertEquals(List.of(), reported.all());
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
                () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(withoutCodec, List.of(MissingColumns.class)).build()).errorCode());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(withoutCodec, List.of(WithoutTables.class)).build());
        assertThrows(IllegalStateException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(withoutCodec, List.of(WithJsons.class)).build());
    }
}
