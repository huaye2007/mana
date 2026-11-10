package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.data.Fixtures.Item;
import cn.managame.data.Fixtures.ItemLog;
import cn.managame.data.Fixtures.ItemLogs;
import cn.managame.data.Fixtures.Items;
import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What a backend bound with {@code metrics(Metrics)} reads: the numbers stats gives, by entity and log type. */
class MetricsTest {
    /** Stands in for a backend: holds what it reads until the meter is closed. */
    private static final class Backend implements Metrics {
        private final Map<String, DoubleSupplier> meters = new ConcurrentHashMap<>();

        private static String key(String name, String... tags) { return name + List.of(tags); }

        @Override public Counter counter(String name, String... tags) { throw new AssertionError("data counts nothing again: " + name); }

        @Override public Meter counter(String name, LongSupplier count, String... tags) { return gauge(name, count::getAsLong, tags); }

        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            if (meters.put(key(name, tags), value) != null) throw new AssertionError(key(name, tags) + " made twice");
            return () -> meters.remove(key(name, tags));
        }

        long read(String name, String... tags) { return (long) meters.get(key(name, tags)).getAsDouble(); }
    }

    private static final String PLAYER = Player.class.getName(), ITEM = Item.class.getName(), ITEM_LOG = ItemLog.class.getName();
    private final Backend backend = new Backend();
    private final MemoryMapper database = new MemoryMapper();
    private final GameData data = GameData.builder()
            .repositories(database, List.of(Players.class, Items.class))
            .logRepositories((type, logs) -> {}, List.of(ItemLogs.class))
            .errorHandler(new ReportedFailures()).logQueueCapacity(2).flushInterval(Duration.ofMinutes(1))
            .metrics(backend).build();
    private final Players players = data.repository(Players.class);

    @AfterEach
    void close() { data.close(); }

    private long players(String name) { return backend.read(name, "entity", PLAYER); }

    @Test
    void reportsChangesWaitingWrittenFailedAndDroppedByEntity() {
        players.insert(new Player(1, "ann"));
        players.insert(new Player(2, "bob"));
        assertEquals(2, players("managame.data.changes.waiting"));
        assertEquals(0, backend.read("managame.data.changes.waiting", "entity", ITEM), "each entity type on its own");
        data.flush();
        assertEquals(0, players("managame.data.changes.waiting"));
        assertEquals(2, players("managame.data.changes.written"));

        database.badRows = entity -> ((Player) entity).id == 2;
        players.update(1L, player -> player.level = 2);
        players.update(2L, player -> player.level = 2);
        assertThrows(DataException.class, data::flush);
        assertEquals(3, players("managame.data.changes.written"));
        assertEquals(2, players("managame.data.changes.write_failures"), "the batch, then the bad row alone");
        assertEquals(1, players("managame.data.changes.dropped"));
    }

    @Test
    void reportsWhatTheCachesHoldAndLoad() {
        database.put(Player.class, new Player(7, "bob"));
        players.get(7L);
        players.get(7L);
        players.get(8L);
        assertEquals(2, players("managame.data.cache.entries"), "7, and 8 remembered as missing");
        assertEquals(1, players("managame.data.cache.hits"));
        assertEquals(2, players("managame.data.cache.misses"));
        assertEquals(2, players("managame.data.cache.loads"));
        assertEquals(0, players("managame.data.cache.load_failures"));
        assertTrue(players("managame.data.cache.load_nanos") > 0);
        assertEquals(0, players("managame.data.cache.evictions"));

        Items items = data.repository(Items.class);
        items.insert(new Item(1, 1001, 1, 5001, 1));
        items.insert(new Item(2, 1001, 2, 5002, 1));
        assertEquals(1, backend.read("managame.data.cache.entries", "entity", ITEM), "a grouped repository's are its groups");
    }

    @Test
    void reportsLogsQueuedRefusedAndWritten() {
        ItemLogs logs = data.repository(ItemLogs.class);
        for (int i = 0; i < 3; i++) logs.insert(new ItemLog(1, 5001, i));
        assertEquals(2, backend.read("managame.data.logs.queued", "log", ITEM_LOG));
        assertEquals(1, backend.read("managame.data.logs.refused", "log", ITEM_LOG));
        data.flushLogs();
        assertEquals(0, backend.read("managame.data.logs.queued", "log", ITEM_LOG));
        assertEquals(2, backend.read("managame.data.logs.written", "log", ITEM_LOG));
        assertEquals(0, backend.read("managame.data.logs.write_failures", "log", ITEM_LOG));
        assertEquals(0, backend.read("managame.data.logs.dropped", "log", ITEM_LOG));
    }

    @Test
    void closeRemovesEveryMeter() {
        assertEquals(2 * 11 + 5, backend.meters.size(), "eleven by entity type, five by log type");
        data.close();
        assertEquals(Map.of(), backend.meters);
    }
}
