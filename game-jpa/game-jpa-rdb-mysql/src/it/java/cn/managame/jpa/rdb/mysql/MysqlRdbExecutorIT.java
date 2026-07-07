package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.annotation.Version;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

public class MysqlRdbExecutorIT {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("game_jpa_it")
            .withUsername("game")
            .withPassword("game");

    private static DataSource dataSource;
    private static MysqlRdbExecutor executor;
    private static RdbEntityMetadata metadata;

    @BeforeAll
    public static void startMysql() throws Exception {
        MYSQL.start();
        dataSource = MysqlDataSourceFactory.builder()
                .jdbcUrl(MYSQL.getJdbcUrl())
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .maximumPoolSize(3)
                .minimumIdle(1)
                .poolName("game-jpa-it")
                .build();
        executor = new MysqlRdbExecutor(dataSource);
        metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        dropTable();
        new MysqlSchemaGenerator(dataSource).synchronizeEntity(metadata, MysqlSchemaGenerator.Mode.CREATE);
    }

    @AfterAll
    public static void stopMysql() throws Exception {
        try {
            dropTable();
        } finally {
            if (executor != null) {
                executor.close();
            }
            MYSQL.stop();
        }
    }

    @Test
    public void executesCrudQueryAndUpsertAgainstRealMysql() {
        Player player = new Player(1L, "ada", 10, 0);

        executor.insert(metadata, player, ExecutorContext.defaultContext());
        Player loaded = executor.findById(metadata, 1L, ExecutorContext.defaultContext());
        assertEquals("ada", loaded.name);

        loaded.level = 11;
        executor.update(metadata, loaded, ExecutorContext.defaultContext());
        assertEquals(1, loaded.version);

        Player replacement = new Player(1L, "grace", 20, loaded.version);
        executor.upsert(metadata, replacement, ExecutorContext.defaultContext());
        Player afterUpsert = executor.findById(metadata, 1L, ExecutorContext.defaultContext());
        assertEquals("grace", afterUpsert.name);
        assertEquals(20, afterUpsert.level);

        executor.batchUpsert(metadata, List.of(
                new Player(2L, "linus", 30, 0),
                new Player(3L, "margaret", 40, 0)), ExecutorContext.defaultContext());

        RdbQuerySpec spec = new RdbQuerySpec().gte("level", 20).orderByAsc("id");
        List<Player> players = executor.query(metadata, spec, ExecutorContext.defaultContext());
        assertEquals(3, players.size());
        assertEquals(3, executor.count(metadata, spec, ExecutorContext.defaultContext()));

        executor.deleteById(metadata, 2L, ExecutorContext.defaultContext());
        assertNull(executor.findById(metadata, 2L, ExecutorContext.defaultContext()));
    }

    private static void dropTable() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS `player_it`");
        }
    }

    @Entity
    @Table(name = "player_it")
    public static class Player {
        @Id
        @Column
        private long id;
        @Column(name = "player_name")
        private String name;
        @Column
        private int level;
        @Version
        @Column
        private long version;

        public Player() {
        }

        public Player(long id, String name, int level, long version) {
            this.id = id;
            this.name = name;
            this.level = level;
            this.version = version;
        }
    }
}
