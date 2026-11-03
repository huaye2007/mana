package cn.managame.example.data;

import cn.managame.data.GameData;
import cn.managame.data.mysql.MysqlEntityMapper;
import cn.managame.example.login.UserRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.System.Logger.Level;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 数据的装配：MySQL 连接池、GameData，以及 GameData 创建的各个 Repository。表由应用自己建，见
 * {@code resources/sql/schema.sql}。连接用 -Dmysql.url（默认本机 managame_example 库）、-Dmysql.user、-Dmysql.password 指定。
 *
 * <p>GameData 要最后关闭：ServerConfig 里 GameRuntime 依赖它，所以 Spring 先关 GameRuntime（业务任务都执行完），
 * 再关 GameData（把还没写的改动写进数据库）。
 */
@Configuration
public class DataConfig {
    private static final System.Logger LOG = System.getLogger(DataConfig.class.getName());
    private static final String DEFAULT_URL = "jdbc:mysql://127.0.0.1:3306/managame_example?createDatabaseIfNotExist=true"
            + "&rewriteBatchedStatements=true&cachePrepStmts=true&prepStmtCacheSqlLimit=1000000";

    @Bean
    public HikariDataSource dataSource(Environment env) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env.getProperty("mysql.url", DEFAULT_URL));
//        config.setUsername(env.getRequiredProperty("mysql.user"));
//        config.setPassword(env.getRequiredProperty("mysql.password"));
        config.setUsername("root");
        config.setPassword("123456");
        return new HikariDataSource(config);
    }

    @Bean
    public GameData gameData(DataSource dataSource) {
        return GameData.builder()
                .repositories(new MysqlEntityMapper(dataSource, new JacksonJsonCodec()), List.of(UserRepository.class))
                .errorHandler(failure -> LOG.log(Level.ERROR, "数据写入失败：" + failure))
                .build();
    }

    @Bean
    public UserRepository userRepository(GameData gameData) { return gameData.repository(UserRepository.class); }
}
