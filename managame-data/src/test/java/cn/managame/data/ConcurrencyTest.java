package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.managame.data.Fixtures.Item;
import cn.managame.data.Fixtures.Items;
import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Business threads change their own players while rounds write them every few milliseconds. */
class ConcurrencyTest {
    private static final int PLAYERS = 64, CHANGES = 200;

    @Test
    void writesTheLastStateOfEveryRowWhileRoundsRun() throws Exception {
        MemoryMapper database = new MemoryMapper();
        ReportedFailures reported = new ReportedFailures();
        try (GameData data = GameData.builder().repositories(database, List.of(Players.class, Items.class)).errorHandler(reported)
                .flushInterval(Duration.ofMillis(5)).cacheExpire(Duration.ofSeconds(10)).writeThreads(2).build();
             ExecutorService threads = Executors.newFixedThreadPool(8)) {
            Players players = data.repository(Players.class);
            Items items = data.repository(Items.class);
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 1; index <= PLAYERS; index++) {
                long id = index;
                futures.add(threads.submit(() -> {
                    Player player = players.getOrCreate(id, key -> new Player(key, "p" + key));
                    for (int change = 1; change <= CHANGES; change++) {
                        player.level = change;
                        player.items.put(change % 7, (long) change);
                        players.update(player);
                        int slot = change % 5;
                        Item item = items.get(GroupKeys.of(id), slot);
                        if (item == null) items.insert(new Item(id * 1000 + change, id, slot, change, 1));
                        else if (change % 3 == 0) items.delete(item);
                        else items.update(GroupKeys.of(id), slot, it -> it.amount++);
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
            data.flush();

            for (Map<String, Object> row : database.rows(Player.class)) assertEquals(CHANGES, row.get("level"));
            for (long id = 1; id <= PLAYERS; id++) {
                long playerId = id;
                List<List<Object>> stored = database.rows(Item.class).stream().filter(row -> row.get("playerId").equals(playerId))
                        .sorted(Comparator.comparing(row -> (Integer) row.get("slot")))
                        .map(row -> List.of(row.get("uid"), row.get("slot"), row.get("amount"))).toList();
                List<List<Object>> inMemory = items.getGroup(GroupKeys.of(id)).values().stream().sorted(Comparator.comparingInt(item -> item.slot))
                        .map(item -> List.<Object>of(item.uid, item.slot, item.amount)).toList();
                assertEquals(inMemory, stored);
            }
            assertEquals(PLAYERS, database.rows(Player.class).size());
            assertEquals(List.of(), reported.all());
        }
    }
}
