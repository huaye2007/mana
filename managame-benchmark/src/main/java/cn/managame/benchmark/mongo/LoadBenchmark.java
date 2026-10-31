package cn.managame.benchmark.mongo;

import cn.managame.benchmark.mongo.MongoServer.Item;
import cn.managame.benchmark.mongo.MongoServer.Items;
import cn.managame.benchmark.mongo.MongoServer.Player;
import cn.managame.benchmark.mongo.MongoServer.Players;
import cn.managame.data.GroupKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Entities loaded per second from a real MongoDB by {@value #THREADS} threads at once, each dropping what it loads
 * from memory first, as players logging in find nothing cached: a player by id (one find), {@value #BATCH} players
 * by {@code getAll} (one {@code $in}), and a group of {@value #GROUP_SIZE} items. Each thread loads ids of its own.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Threads(LoadBenchmark.THREADS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class LoadBenchmark {
    static final int THREADS = 8, PLAYERS = 8000, GROUPS = 800, GROUP_SIZE = 20, BATCH = 100;

    @State(Scope.Benchmark)
    public static class Server {
        MongoServer server;
        Players players;
        Items items;

        @Setup(Level.Trial) public void start() {
            server = new MongoServer();
            players = server.data.repository(Players.class);
            items = server.data.repository(Items.class);
            for (long id = 0; id < PLAYERS; id++) players.insert(new Player(id));
            for (long uid = 0; uid < GROUPS * GROUP_SIZE; uid++) items.insert(new Item(uid, uid / GROUP_SIZE, (int) (uid % GROUP_SIZE)));
            server.data.flush();
        }

        @TearDown(Level.Trial) public void stop() { server.close(); }
    }

    /** The ids of one thread: a range of players and of groups no other thread loads. */
    @State(Scope.Thread)
    public static class Ids {
        long firstPlayer, players, firstGroup, groups, nextPlayer, nextGroup;
        final List<Long> batch = new ArrayList<>(BATCH);

        @Setup(Level.Trial) public void start(ThreadParams thread) {
            players = PLAYERS / thread.getThreadCount();
            groups = GROUPS / thread.getThreadCount();
            firstPlayer = thread.getThreadIndex() * players;
            firstGroup = thread.getThreadIndex() * groups;
        }

        long player() { return firstPlayer + nextPlayer++ % players; }

        long group() { return firstGroup + nextGroup++ % groups; }
    }

    @Benchmark public Player get(Server server, Ids ids) {
        long id = ids.player();
        server.players.evict(id);
        return server.players.get(id);
    }

    @Benchmark @OperationsPerInvocation(BATCH) public Map<Long, Player> getAll(Server server, Ids ids) {
        ids.batch.clear();
        for (int i = 0; i < BATCH; i++) {
            long id = ids.player();
            server.players.evict(id);
            ids.batch.add(id);
        }
        return server.players.getAll(ids.batch);
    }

    @Benchmark @OperationsPerInvocation(GROUP_SIZE) public Map<Integer, Item> getGroup(Server server, Ids ids) {
        GroupKeys group = GroupKeys.of(ids.group());
        server.items.evict(group);
        return server.items.getGroup(group);
    }
}
