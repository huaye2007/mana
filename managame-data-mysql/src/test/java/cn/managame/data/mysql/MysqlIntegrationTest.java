package cn.managame.data.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.managame.data.GameData;
import cn.managame.data.GroupKeys;
import cn.managame.data.GroupRepository;
import cn.managame.data.LogRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.mysql.annotation.PartitionKey;
import cn.managame.data.mysql.annotation.PartitionType;
import cn.managame.data.mysql.annotation.Table;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Against a real MySQL, skipped unless its address is given. Use a database that can be thrown away: the test creates
 * and drops tables named {@code managame_it_*}, and refuses to run when they exist.
 *
 * <pre>
 * MANAGAME_DATA_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/scratch?rewriteBatchedStatements=true
 * MANAGAME_DATA_MYSQL_USER=... MANAGAME_DATA_MYSQL_PASSWORD=...
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "MANAGAME_DATA_MYSQL_URL", matches = ".+")
class MysqlIntegrationTest {
    @Table(name = "managame_it_player")
    static class Player {
        @Id long id;
        String name;
        int level;
        Map<Integer, Long> items = new ConcurrentHashMap<>();

        Player() {}

        Player(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Table(name = "managame_it_item")
    static class Item {
        @Id long uid;
        @GroupKey long playerId;
        @MapKey int slot;
        int amount;

        Item() {}

        Item(long uid, long playerId, int slot, int amount) {
            this.uid = uid;
            this.playerId = playerId;
            this.slot = slot;
            this.amount = amount;
        }
    }

    @Table(name = "managame_it_log")
    static class Log {
        long playerId;
        @PartitionKey(type = PartitionType.DAY) long createdAt;

        Log() {}

        Log(long playerId, long createdAt) {
            this.playerId = playerId;
            this.createdAt = createdAt;
        }
    }

    static final class Players extends SingleRepository<Long, Player> {}

    static final class Items extends GroupRepository<Integer, Item> {}

    static final class Logs extends LogRepository<Log> {}

    @Test
    void writesToMysql() throws SQLException {
        MysqlDataSource source = new MysqlDataSource();
        source.setURL(System.getenv("MANAGAME_DATA_MYSQL_URL"));
        source.setUser(System.getenv("MANAGAME_DATA_MYSQL_USER"));
        source.setPassword(System.getenv("MANAGAME_DATA_MYSQL_PASSWORD"));
        try (Connection connection = source.getConnection(); ResultSet tables = connection.getMetaData()
                .getTables(connection.getCatalog(), null, "managame_it_%", null)) {
            if (tables.next()) throw new IllegalStateException("managame_it_* tables exist; use a database that can be thrown away");
        }
        execute(source, "CREATE TABLE managame_it_player (id BIGINT PRIMARY KEY, name VARCHAR(16), level INT NOT NULL, items JSON)");
        execute(source, "CREATE TABLE managame_it_item (uid BIGINT PRIMARY KEY, player_id BIGINT NOT NULL, slot INT NOT NULL,"
                + " amount INT NOT NULL, UNIQUE KEY (player_id, slot))");
        execute(source, "CREATE TABLE managame_it_log (player_id BIGINT, created_at BIGINT)");
        long day = Instant.parse("2026-10-31T08:00:00Z").toEpochMilli();
        try (GameData data = GameData.builder()
                .repositories(new MysqlEntityMapper(source, new Fixtures.Json()), List.of(Players.class, Items.class))
                .logRepositories(new MysqlLogWriter(source), List.of(Logs.class))
                .flushInterval(Duration.ofMinutes(1)).build()) {
            Players players = data.repository(Players.class);
            Items items = data.repository(Items.class);
            for (long id = 1; id <= 250; id++) players.insert(new Player(id, "p" + id));
            data.flush();
            for (long id = 1; id <= 250; id++) players.update(id, player -> player.level = (int) player.id * 2);
            players.get(3L).items.put(5001, 9L);
            players.update(players.get(3L));
            players.delete(250L);
            items.insert(new Item(1, 1, 0, 1));
            items.insert(new Item(2, 1, 1, 1));
            data.flush();
            items.delete(items.get(GroupKeys.of(1L), 0));
            items.insert(new Item(3, 1, 0, 7));
            data.repository(Logs.class).insert(new Log(1, day));
            data.flush();

            players.evict(3L);
            assertEquals(Map.of(5001, 9L), players.get(3L).items);
            assertNull(players.get(250L));
            assertEquals(498L, count(source, "SELECT level FROM managame_it_player WHERE id = 249"));
            items.evict(GroupKeys.of(1L));
            assertEquals(7, items.get(GroupKeys.of(1L), 0).amount);
            assertEquals(1L, count(source, "SELECT COUNT(*) FROM managame_it_log_20261031"));
        } finally {
            for (String table : List.of("managame_it_player", "managame_it_item", "managame_it_log", "managame_it_log_20261031"))
                execute(source, "DROP TABLE IF EXISTS " + table);
        }
    }

    private static void execute(MysqlDataSource source, String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(MysqlDataSource source, String sql) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
