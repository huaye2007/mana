package cn.managame.benchmark.mysqlserver;

import cn.managame.data.GameData;
import cn.managame.data.GroupRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.mysql.JsonCodec;
import cn.managame.data.mysql.MysqlEntityMapper;
import cn.managame.data.mysql.annotation.Table;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real MySQL for the mysqlserver benchmarks, given by {@code MANAGAME_DATA_MYSQL_URL} (with
 * {@code rewriteBatchedStatements=true}), {@code MANAGAME_DATA_MYSQL_USER} and {@code MANAGAME_DATA_MYSQL_PASSWORD},
 * behind a pool of 16 connections. Its tables, named {@code managame_bench_*}, are made afresh for every trial: give a
 * database that can be thrown away.
 */
final class MysqlServer implements AutoCloseable {
    @Table(name = "managame_bench_player")
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

    /** A player's bag in one JSON column, as games keep a collection that changes often. */
    @Table(name = "managame_bench_bag")
    public static class Bag {
        @Id long playerId;
        Map<Integer, Long> items = new ConcurrentHashMap<>();

        public Bag() {}

        Bag(long playerId) {
            this.playerId = playerId;
            for (int item = 0; item < BAG_ITEMS; item++) items.put(5000 + item, 1L);
        }
    }

    @Table(name = "managame_bench_item")
    public static class Item {
        @Id long uid;
        @GroupKey long playerId;
        @MapKey int slot;
        int configId;
        int amount;

        public Item() {}

        Item(long uid, long playerId, int slot) {
            this.uid = uid;
            this.playerId = playerId;
            this.slot = slot;
            configId = 5000 + slot;
            amount = 1;
        }
    }

    public static final class Players extends SingleRepository<Long, Player> {}

    public static final class Bags extends SingleRepository<Long, Bag> {}

    public static final class Items extends GroupRepository<Integer, Item> {}

    static final int BAG_ITEMS = 50;

    static final class Json implements JsonCodec {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override public String encode(Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override public Object decode(String json, Type type) {
            try {
                return mapper.readValue(json, mapper.constructType(type));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    final HikariDataSource pool;
    final GameData data;

    /** The tables made afresh, and a GameData over them that writes only on {@code flush()}, on the calling thread. */
    MysqlServer() throws SQLException {
        String url = System.getenv("MANAGAME_DATA_MYSQL_URL");
        if (url == null || url.isBlank()) throw new IllegalStateException("set MANAGAME_DATA_MYSQL_URL, _USER and _PASSWORD");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv("MANAGAME_DATA_MYSQL_USER"));
        config.setPassword(System.getenv("MANAGAME_DATA_MYSQL_PASSWORD"));
        config.setMaximumPoolSize(16);
        pool = new HikariDataSource(config);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("player", "bag", "item")) statement.execute("DROP TABLE IF EXISTS managame_bench_" + table);
            statement.execute("CREATE TABLE managame_bench_player (id BIGINT PRIMARY KEY, name VARCHAR(32), level INT NOT NULL,"
                    + " exp BIGINT NOT NULL, gold BIGINT NOT NULL, diamond INT NOT NULL, vip INT NOT NULL,"
                    + " last_login BIGINT NOT NULL, power BIGINT NOT NULL, guild_id BIGINT NOT NULL)");
            statement.execute("CREATE TABLE managame_bench_bag (player_id BIGINT PRIMARY KEY, items JSON NOT NULL)");
            statement.execute("CREATE TABLE managame_bench_item (uid BIGINT PRIMARY KEY, player_id BIGINT NOT NULL, slot INT NOT NULL,"
                    + " config_id INT NOT NULL, amount INT NOT NULL, UNIQUE KEY (player_id, slot))");
        }
        data = GameData.builder().errorHandler(failure -> {})
                .repositories(new MysqlEntityMapper(pool, new Json()), List.of(Players.class, Bags.class, Items.class))
                .writeThreads(1).flushInterval(Duration.ofHours(1)).cacheExpire(Duration.ofHours(2)).build();
    }

    @Override public void close() {
        data.close();
        pool.close();
    }
}
