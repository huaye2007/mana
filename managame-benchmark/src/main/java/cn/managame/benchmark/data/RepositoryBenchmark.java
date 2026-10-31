package cn.managame.benchmark.data;

import cn.managame.benchmark.data.DataFixture.Item;
import cn.managame.benchmark.data.DataFixture.Items;
import cn.managame.benchmark.data.DataFixture.Player;
import cn.managame.benchmark.data.DataFixture.Players;
import cn.managame.data.GroupKeys;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What business code pays on its own thread with every entity in memory: reading a player, reading a player's bag,
 * and recording that a player changed, which merges into the change already waiting for it. No save runs.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RepositoryBenchmark {
    static final int PLAYERS = 1024, SLOTS = 8;
    private final Long[] ids = new Long[PLAYERS];
    private final GroupKeys[] groupKeys = new GroupKeys[PLAYERS];
    private final Player[] cachedPlayers = new Player[PLAYERS];
    private DataFixture fixture;
    private Players players;
    private Items items;
    private int next;

    @Setup(Level.Trial) public void start() {
        fixture = new DataFixture();
        players = fixture.players();
        items = fixture.items();
        for (int i = 0; i < PLAYERS; i++) {
            ids[i] = (long) i;
            groupKeys[i] = GroupKeys.of((long) i);
            players.insert(new Player(i));
            for (int slot = 0; slot < SLOTS; slot++) items.insert(new Item((long) i * SLOTS + slot, i, slot));
        }
        fixture.data.flush();
        for (int i = 0; i < PLAYERS; i++) cachedPlayers[i] = players.get(ids[i]);
    }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    @Benchmark public Player get() { return players.get(ids[next++ & (PLAYERS - 1)]); }

    @Benchmark public Map<Integer, Item> getGroup() { return items.getGroup(groupKeys[next++ & (PLAYERS - 1)]); }

    @Benchmark public void update() {
        Player player = cachedPlayers[next++ & (PLAYERS - 1)];
        player.gold++;
        players.update(player);
    }
}
