package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.Fixtures.Item;
import cn.managame.data.Fixtures.ItemLog;
import cn.managame.data.Fixtures.ItemLogs;
import cn.managame.data.Fixtures.Items;
import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What stats reports as rows are cached, wait, are written, fail and are dropped. */
class StatsTest {
    private final MemoryMapper database = new MemoryMapper();
    private final List<Object> writtenLogs = new CopyOnWriteArrayList<>();
    private final GameData data = GameData.builder()
            .repositories(database, List.of(Players.class, Items.class))
            .logRepositories((type, logs) -> writtenLogs.addAll(logs), List.of(ItemLogs.class))
            .errorHandler(new ReportedFailures()).logQueueCapacity(2).flushInterval(Duration.ofMinutes(1)).build();
    private final Players players = data.repository(Players.class);

    @AfterEach
    void close() { data.close(); }

    private EntityStats players() { return data.stats().entities().getFirst(); }

    @Test
    void listsEveryRepositoryInOrder() {
        DataStats stats = data.stats();
        assertEquals(List.of(Player.class, Item.class), stats.entities().stream().map(EntityStats::entityType).toList());
        assertEquals(List.of(ItemLog.class), stats.logs().stream().map(LogStats::logType).toList());
    }

    @Test
    void countsChangesWaitingWrittenFailedAndDropped() {
        players.insert(new Player(1, "ann"));
        players.insert(new Player(2, "bob"));
        assertEquals(2, players().waiting());
        assertEquals(2, players().cached());
        data.flush();
        assertEquals(0, players().waiting());
        assertEquals(2, players().written());

        database.badRows = entity -> ((Player) entity).id == 2;
        players.update(1L, player -> player.level = 2);
        players.update(2L, player -> player.level = 2);
        assertThrows(DataException.class, data::flush);
        EntityStats afterBadRow = players();
        assertEquals(3, afterBadRow.written(), "the good row of the batch, written alone");
        assertEquals(2, afterBadRow.failures(), "the batch, then the bad row alone");
        assertEquals(1, afterBadRow.dropped());

        database.badRows = entity -> false;
        database.unreachable = true;
        players.update(1L, player -> player.level = 3);
        assertThrows(DataException.class, data::flush);
        assertEquals(1, players().waiting(), "kept for the next round");
        assertEquals(3, players().failures());
        assertEquals(3, players().written());

        database.unreachable = false;
        data.flush();
        assertEquals(0, players().waiting());
        assertEquals(4, players().written());
    }

    @Test
    void countsCacheHitsMissesAndLoads() {
        database.put(Player.class, new Player(7, "bob"));
        players.get(7L);
        players.get(7L);
        players.get(8L);
        players.getAll(List.of(7L, 9L));
        var cache = players().cache();
        assertEquals(2, cache.hitCount(), "7 the second time, then in getAll");
        assertEquals(3, cache.missCount(), "7 the first time, 8, then 9 in getAll");
        assertEquals(2, cache.loadSuccessCount(), "7 and 8; getAll reads 9 itself");
    }

    @Test
    void countsLogsQueuedRefusedAndWritten() {
        ItemLogs logs = data.repository(ItemLogs.class);
        for (int i = 0; i < 3; i++) logs.insert(new ItemLog(1, 5001, i));
        LogStats queued = data.stats().logs().getFirst();
        assertEquals(2, queued.queued());
        assertEquals(1, queued.refused());
        data.flush();
        LogStats written = data.stats().logs().getFirst();
        assertEquals(0, written.queued());
        assertEquals(2, written.written());
        assertEquals(List.of(new ItemLog(1, 5001, 0), new ItemLog(1, 5001, 1)), writtenLogs);
    }
}
