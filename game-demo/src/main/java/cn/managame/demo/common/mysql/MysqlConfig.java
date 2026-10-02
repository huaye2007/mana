package cn.managame.demo.common.mysql;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
@PropertySource("classpath:application.properties")
public class MysqlConfig {

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource() {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Configure game.db.url in application.properties before starting game-demo");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        return new HikariDataSource(config);
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
