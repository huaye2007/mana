package cn.managame.benchmark.mysql;

import cn.managame.data.GameData;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.Id;
import cn.managame.data.mysql.MysqlEntityMapper;
import cn.managame.data.mysql.annotation.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcConnectionPool;
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
 * Rows saved per second through {@code MysqlEntityMapper} into an in-memory H2 database in MySQL mode behind a
 * connection pool: {@value #ROWS} changed players per {@code flush()}. {@code update} sends UPDATEs of up to 100 rows
 * each; {@code insertDelete} inserts the rows in batches and deletes them again, in two rounds. The database's own work
 * is in the score; the framework's share shows in the CPU of the calling thread, which runs the round.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class SaveThroughputBenchmark {
    static final int ROWS = 1000;
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Table(name = "player")
    public static class Player {
        @Id long id;
        String name;
        int level;
        long exp;
        long gold;
        int diamond;
        int vip;
        long lastLogin;
        long power;
        long guildId;

        public Player() {}

        Player(long id) {
            this.id = id;
            name = "player" + id;
            level = 1;
        }
    }

    public static final class Players extends SingleRepository<Long, Player> {}

    private final Player[] entities = new Player[ROWS];
    private JdbcConnectionPool pool;
    private GameData data;
    private Players players;
    private long nextId = ROWS;

    @Setup(Level.Trial) public void start() throws SQLException {
        pool = JdbcConnectionPool.create("jdbc:h2:mem:bench" + DATABASES.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(16);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player (id BIGINT PRIMARY KEY, name VARCHAR(32), level INT NOT NULL, exp BIGINT NOT NULL,"
                    + " gold BIGINT NOT NULL, diamond INT NOT NULL, vip INT NOT NULL, last_login BIGINT NOT NULL,"
                    + " power BIGINT NOT NULL, guild_id BIGINT NOT NULL)");
        }
        data = GameData.builder().errorHandler(failure -> {}).repositories(new MysqlEntityMapper(pool), List.of(Players.class)).writeThreads(1)
                .flushInterval(Duration.ofHours(1)).cacheExpire(Duration.ofHours(2)).build();
        players = data.repository(Players.class);
        for (int i = 0; i < ROWS; i++) {
            entities[i] = new Player(i);
            players.insert(entities[i]);
        }
        data.flush();
    }

    @TearDown(Level.Trial) public void stop() {
        data.close();
        pool.dispose();
    }

    @Benchmark @OperationsPerInvocation(ROWS) public void update() {
        for (Player player : entities) {
            player.gold++;
            players.update(player);
        }
        data.flush();
    }

    @Benchmark @OperationsPerInvocation(2 * ROWS) public void insertDelete() {
        long first = nextId;
        for (int i = 0; i < ROWS; i++) players.insert(new Player(nextId++));
        data.flush();
        for (long id = first; id < nextId; id++) players.delete(id);
        data.flush();
    }
}
