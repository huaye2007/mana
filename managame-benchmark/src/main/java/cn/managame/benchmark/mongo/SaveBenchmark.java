package cn.managame.benchmark.mongo;

import cn.managame.benchmark.mongo.MongoServer.Bag;
import cn.managame.benchmark.mongo.MongoServer.Bags;
import cn.managame.benchmark.mongo.MongoServer.Item;
import cn.managame.benchmark.mongo.MongoServer.Items;
import cn.managame.benchmark.mongo.MongoServer.Player;
import cn.managame.benchmark.mongo.MongoServer.Players;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Rows a round writes per second into a real MongoDB, {@value #ROWS} changed per {@code flush()}, which runs on the
 * calling thread: players of ten scalar columns, bags of one embedded document of {@value MongoServer#BAG_ITEMS} entries,
 * items kept in groups of {@value #GROUP_SIZE}, and players inserted then deleted. The caller's CPU is the framework,
 * the driver and the encoding; the rest of the time is the server.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class SaveBenchmark {
    static final int ROWS = 1000, GROUP_SIZE = 20;
    private final Player[] players = new Player[ROWS];
    private final Bag[] bags = new Bag[ROWS];
    private final Item[] items = new Item[ROWS];
    private MongoServer server;
    private Players playerRepository;
    private Bags bagRepository;
    private Items itemRepository;
    private long nextId = ROWS;

    @Setup(Level.Trial) public void start() {
        server = new MongoServer();
        playerRepository = server.data.repository(Players.class);
        bagRepository = server.data.repository(Bags.class);
        itemRepository = server.data.repository(Items.class);
        for (int i = 0; i < ROWS; i++) {
            playerRepository.insert(players[i] = new Player(i));
            bagRepository.insert(bags[i] = new Bag(i));
            itemRepository.insert(items[i] = new Item(i, i / GROUP_SIZE, i % GROUP_SIZE));
        }
        server.data.flush();
    }

    @TearDown(Level.Trial) public void stop() { server.close(); }

    @Benchmark @OperationsPerInvocation(ROWS) public void update() {
        for (Player player : players) {
            player.gold++;
            playerRepository.update(player);
        }
        server.data.flush();
    }

    @Benchmark @OperationsPerInvocation(ROWS) public void updateJson() {
        for (Bag bag : bags) {
            bag.items.merge(5000, 1L, Long::sum);
            bagRepository.update(bag);
        }
        server.data.flush();
    }

    @Benchmark @OperationsPerInvocation(ROWS) public void updateItems() {
        for (Item item : items) {
            item.amount++;
            itemRepository.update(item);
        }
        server.data.flush();
    }

    @Benchmark @OperationsPerInvocation(2 * ROWS) public void insertDelete() {
        long first = nextId;
        for (int i = 0; i < ROWS; i++) playerRepository.insert(new Player(nextId++));
        server.data.flush();
        for (long id = first; id < nextId; id++) playerRepository.delete(id);
        server.data.flush();
    }
}
