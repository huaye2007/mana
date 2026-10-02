package cn.managame.demo.common.mysql;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import cn.managame.data.GameData;
import cn.managame.data.GameDataBuilder;
import cn.managame.demo.bus.user.UserRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

@Configuration
@PropertySource("classpath:application.properties")
public class MysqlConfig {

    @Bean
    public static PropertySourcesPlaceholderConfigurer properties() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    public GameData gameData(DataSource source) {
        return GameDataBuilder.builder().mysql(source).repositories(UserRepository.class).build();
    }

    @Bean
    public UserRepository userRepository(GameData data) {
        return data.repository(UserRepository.class);
    }

    @Value("${game.db.url:}")
    private String url;

    @Value("${game.db.username:}")
    private String username;

    @Value("${game.db.password:}")
    private String password;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
