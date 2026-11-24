package cn.managame.example.data;

import cn.managame.data.GameData;
import cn.managame.data.mysql.MysqlEntityMapper;
import cn.managame.data.mysql.MysqlLogWriter;
import cn.managame.example.ai.AiAuditLogRepository;
import cn.managame.example.ai.PlayerAiStateRepository;
import cn.managame.example.login.UserRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.System.Logger.Level;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 数据的装配：MySQL 连接池、GameData，以及 GameData 创建的各个实体仓储与日志仓储。表结构见 {@code resources/sql/schema.sql}。
 * 连接用 -Dmysql.url（默认本机 managame_example 库）、-Dmysql.user、-Dmysql.password 指定。
 *
 * <p>GameData 要最后关闭：ServerConfig 里 GameRuntime 依赖它，所以 Spring 先关 GameRuntime（业务任务都执行完），
 * 再关 GameData（把还没写的实体改动和审计日志全部写进数据库）。
 */
@Configuration
public class DataConfig {
    private static final System.Logger LOG = System.getLogger(DataConfig.class.getName());
    private static final String DEFAULT_URL = "jdbc:mysql://127.0.0.1:3306/managame_example?createDatabaseIfNotExist=true"
            + "&rewriteBatchedStatements=true&cachePrepStmts=true&prepStmtCacheSqlLimit=1000000";
    private static final String ENSURE_AI_AUDIT_LOG = """
            CREATE TABLE IF NOT EXISTS `ai_audit_log` (
                `id`         BIGINT       NOT NULL,
                `kind`       VARCHAR(32)  NOT NULL,
                `caller`     VARCHAR(32)  NOT NULL,
                `role_id`    BIGINT       NOT NULL,
                `name`       VARCHAR(64)  NOT NULL,
                `trace_id`   VARCHAR(128) NOT NULL,
                `detail`     TEXT         NOT NULL,
                `code`       INT          NOT NULL,
                `tokens`     INT          NOT NULL,
                `created_at` BIGINT       NOT NULL,
                PRIMARY KEY (`id`)
            )""";

    @Bean
    public HikariDataSource dataSource(Environment env) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getProperty("mysql.url", DEFAULT_URL));
        config.setUsername(env.getRequiredProperty("mysql.user"));
        config.setPassword(env.getRequiredProperty("mysql.password"));
        return new HikariDataSource(config);
    }

    @Bean
    public GameData gameData(DataSource dataSource) throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate(ENSURE_AI_AUDIT_LOG);
        }
        return GameData.builder()
                .repositories(new MysqlEntityMapper(dataSource, new JacksonJsonCodec()),
                        List.of(UserRepository.class, PlayerAiStateRepository.class))
                .logRepositories(new MysqlLogWriter(dataSource), List.of(AiAuditLogRepository.class))
                .errorHandler(failure -> LOG.log(Level.ERROR, "数据写入失败：" + failure))
                .build();
    }

    @Bean
    public UserRepository userRepository(GameData gameData) { return gameData.repository(UserRepository.class); }

    @Bean
    public PlayerAiStateRepository playerAiStateRepository(GameData gameData) {
        return gameData.repository(PlayerAiStateRepository.class);
    }

    @Bean
    public AiAuditLogRepository aiAuditLogRepository(GameData gameData) {
        return gameData.repository(AiAuditLogRepository.class);
    }
}
