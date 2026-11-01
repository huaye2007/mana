package cn.managame.example;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** The game owns its schema; managame-data runs no DDL. MySQL syntax, which H2 in MySQL mode reads too. */
final class Schema {
    private static final String[] TABLES = {
        "CREATE TABLE IF NOT EXISTS player (id BIGINT PRIMARY KEY, name VARCHAR(32) NOT NULL, gold BIGINT NOT NULL,"
                + " best_rank INT NOT NULL, last_logout BIGINT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS item (uid BIGINT PRIMARY KEY, player_id BIGINT NOT NULL, config_id INT NOT NULL,"
                + " amount INT NOT NULL, UNIQUE (player_id, config_id))",
        "CREATE TABLE IF NOT EXISTS gold_log (player_id BIGINT NOT NULL, delta BIGINT NOT NULL, reason VARCHAR(64) NOT NULL,"
                + " created_at BIGINT NOT NULL)",
    };

    private Schema() {}

    static void create(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : TABLES) statement.execute(table);
        }
    }
}
