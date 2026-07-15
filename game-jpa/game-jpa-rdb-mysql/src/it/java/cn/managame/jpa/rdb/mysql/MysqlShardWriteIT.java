package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.core.annotation.ShardKey;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * 分表写入集成测试：验证写路径不会创建缺失分表，以及显式建表后可按 physicalTableName 路由。
 * 需要 Docker（testcontainers MySQL），通过 {@code mvn -Pintegration-tests verify} 运行。
 */
public class MysqlShardWriteIT {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("game_jpa_it")
            .withUsername("game")
            .withPassword("game");

    private static final List<String> PHYSICAL_TABLES =
            List.of("shard_log_1", "shard_log_2", "shard_log_7", "shard_log_missing");

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
                .poolName("game-jpa-shard-it")
                .build();
        executor = new MysqlRdbExecutor(dataSource);
        metadata = new RdbEntityMetadataResolver().resolve(ShardLog.class);
        dropTables();
    }

    @AfterAll
    public static void stopMysql() throws Exception {
        try {
            dropTables();
        } finally {
            if (executor != null) {
                executor.close();
            }
            MYSQL.stop();
        }
    }

    @Test
    public void batchInsertDoesNotCreateMissingPhysicalTable() {
        ExecutorContext shard7 = ExecutorContext.of("default", null, "shard_log_7");

        assertThrows(GameJpaException.class, () -> executor.batchInsert(metadata, List.of(
                new ShardLog(1L, 7L, "a"),
                new ShardLog(2L, 7L, "b")), shard7));
    }

    @Test
    public void batchInsertRoutesToDistinctPhysicalTables() throws Exception {
        MysqlSchemaGenerator generator = new MysqlSchemaGenerator(dataSource);
        generator.createTable(metadata, "shard_log_1");
        generator.createTable(metadata, "shard_log_2");
        executor.batchInsert(metadata, List.of(new ShardLog(10L, 1L, "x")),
                ExecutorContext.of("default", null, "shard_log_1"));
        executor.batchInsert(metadata, List.of(new ShardLog(20L, 2L, "y")),
                ExecutorContext.of("default", null, "shard_log_2"));

        assertEquals(1, rowCount("shard_log_1"));
        assertEquals(1, rowCount("shard_log_2"));
    }

    private static int rowCount(String table) throws Exception {
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void dropTables() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement()) {
            for (String table : PHYSICAL_TABLES) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }

    @Entity
    @Table(name = "shard_log")
    public static class ShardLog {
        @Id
        @Column
        private long id;
        @ShardKey
        @Column
        private long serverId;
        @Column(length = 64)
        private String name;

        public ShardLog() {
        }

        public ShardLog(long id, long serverId, String name) {
            this.id = id;
            this.serverId = serverId;
            this.name = name;
        }
    }
}
