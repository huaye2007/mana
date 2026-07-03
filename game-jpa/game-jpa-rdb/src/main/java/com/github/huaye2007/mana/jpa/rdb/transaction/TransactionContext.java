package com.github.huaye2007.mana.jpa.rdb.transaction;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 事务上下文。
 * 在事务边界内共享同一个 Connection，保证原子性。
 * 同时追踪当前绑定的 DataSource，用于检测嵌套事务跨数据源的误用。
 */
public class TransactionContext {

    private static final ThreadLocal<Connection> CURRENT_CONN = new ThreadLocal<>();
    private static final ThreadLocal<DataSource> CURRENT_DS = new ThreadLocal<>();

    /**
     * 绑定事务连接和数据源到当前线程
     */
    public static void bind(Connection connection, DataSource dataSource) {
        CURRENT_CONN.set(connection);
        CURRENT_DS.set(dataSource);
    }

    /**
     * 获取当前线程的事务连接（无事务时返回 null）
     */
    public static Connection current() {
        return CURRENT_CONN.get();
    }

    /**
     * 获取当前线程绑定的数据源（无事务时返回 null）
     */
    public static DataSource currentDataSource() {
        return CURRENT_DS.get();
    }

    /**
     * 清除当前线程的事务上下文
     */
    public static void clear() {
        CURRENT_CONN.remove();
        CURRENT_DS.remove();
    }

    /**
     * 当前线程是否在事务中
     */
    public static boolean isActive() {
        return CURRENT_CONN.get() != null;
    }
}
