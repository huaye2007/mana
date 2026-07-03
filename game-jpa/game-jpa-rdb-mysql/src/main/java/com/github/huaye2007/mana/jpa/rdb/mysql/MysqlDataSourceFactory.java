package com.github.huaye2007.mana.jpa.rdb.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL 数据源工厂。
 * <p>
 * 提供基于 HikariCP 连接池的 DataSource 创建，针对游戏服务器场景预设合理默认值。
 * 游戏服务器特点：连接数不需要太多（单进程通常 10-30），但要求低延迟和快速故障检测。
 *
 * <pre>{@code
 * // 基本用法
 * DataSource ds = MysqlDataSourceFactory.builder()
 *     .jdbcUrl("jdbc:mysql://localhost:3306/game_db")
 *     .username("root")
 *     .password("123456")
 *     .build();
 *
 * // 自定义连接池参数
 * DataSource ds = MysqlDataSourceFactory.builder()
 *     .jdbcUrl("jdbc:mysql://localhost:3306/game_db")
 *     .username("root")
 *     .password("123456")
 *     .maximumPoolSize(20)
 *     .minimumIdle(5)
 *     .connectionTimeoutMs(3000)
 *     .build();
 * }</pre>
 */
public class MysqlDataSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(MysqlDataSourceFactory.class);

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private int maximumPoolSize = 10;
        private int minimumIdle = 5;
        private long connectionTimeoutMs = 3000;
        private long idleTimeoutMs = 600_000;       // 10 分钟
        private long maxLifetimeMs = 1_800_000;      // 30 分钟
        private long keepaliveTimeMs = 30_000;       // 30 秒
        private long leakDetectionThresholdMs = 0;   // 默认关闭
        private String poolName = "game-hikari";
        private final Properties dataSourceProperties = new Properties();

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /** 最大连接数，游戏服务器建议 10-30 */
        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        /** 最小空闲连接数 */
        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }

        /** 获取连接超时（毫秒），游戏场景建议 3000ms 以内 */
        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /** 空闲连接超时（毫秒） */
        public Builder idleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        /** 连接最大存活时间（毫秒） */
        public Builder maxLifetimeMs(long maxLifetimeMs) {
            this.maxLifetimeMs = maxLifetimeMs;
            return this;
        }

        /** 连接保活间隔（毫秒），防止被 MySQL wait_timeout 断开 */
        public Builder keepaliveTimeMs(long keepaliveTimeMs) {
            this.keepaliveTimeMs = keepaliveTimeMs;
            return this;
        }

        /** 连接泄漏检测阈值（毫秒），开发期建议设为 5000-10000 */
        public Builder leakDetectionThresholdMs(long leakDetectionThresholdMs) {
            this.leakDetectionThresholdMs = leakDetectionThresholdMs;
            return this;
        }

        public Builder poolName(String poolName) {
            this.poolName = poolName;
            return this;
        }

        /** 添加 MySQL JDBC 驱动参数 */
        public Builder dataSourceProperty(String key, String value) {
            this.dataSourceProperties.setProperty(key, value);
            return this;
        }

        /**
         * 构建 HikariCP DataSource。
         * 需要 classpath 中存在 com.zaxxer:HikariCP 依赖。
         */
        public DataSource build() {
            if (jdbcUrl == null || jdbcUrl.isEmpty()) {
                throw new IllegalArgumentException("jdbcUrl is required");
            }
            try {
                return createHikariDataSource();
            } catch (NoClassDefFoundError e) {
                throw new IllegalStateException(
                        "HikariCP not found on classpath. Add dependency: " +
                        "com.zaxxer:HikariCP:5.1.0", e);
            }
        }

        private DataSource createHikariDataSource() {
            HikariConfig config = new HikariConfig();

            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(maximumPoolSize);
            config.setMinimumIdle(minimumIdle);
            config.setConnectionTimeout(connectionTimeoutMs);
            config.setIdleTimeout(idleTimeoutMs);
            config.setMaxLifetime(maxLifetimeMs);
            config.setKeepaliveTime(keepaliveTimeMs);
            config.setPoolName(poolName);

            if (leakDetectionThresholdMs > 0) {
                config.setLeakDetectionThreshold(leakDetectionThresholdMs);
            }

            // 游戏服务器推荐的 MySQL JDBC 参数
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");

            // 用户自定义参数
            for (String key : dataSourceProperties.stringPropertyNames()) {
                config.addDataSourceProperty(key, dataSourceProperties.getProperty(key));
            }

            HikariDataSource ds = new HikariDataSource(config);
            log.info("[MysqlDataSourceFactory] Created HikariCP pool '{}' (max={}, min={})",
                    poolName, maximumPoolSize, minimumIdle);
            return ds;
        }
    }
}
