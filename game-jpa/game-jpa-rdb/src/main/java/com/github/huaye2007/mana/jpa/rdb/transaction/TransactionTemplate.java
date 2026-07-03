package com.github.huaye2007.mana.jpa.rdb.transaction;

import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.registry.DataSourceRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.function.Supplier;

/**
 * 事务模板。
 * 提供编程式事务边界控制，支持多数据源。
 * <p>
 * 使用默认数据源执行事务，或通过 {@link #execute(String, Supplier)} 指定数据源。
 */
public class TransactionTemplate {

    private final DataSourceRegistry<DataSource> dataSourceRegistry;

    /**
     * 基于数据源注册中心创建事务模板，支持多数据源。
     *
     * @param dataSourceRegistry 数据源注册中心
     */
    public TransactionTemplate(DataSourceRegistry<DataSource> dataSourceRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 在默认数据源的事务中执行操作
     */
    public <T> T execute(Supplier<T> action) {
        return executeWithDataSource(dataSourceRegistry.getDefault(), action);
    }

    /**
     * 在指定数据源的事务中执行操作。
     *
     * @param dataSourceName 数据源名称
     * @param action         要执行的操作
     */
    public <T> T execute(String dataSourceName, Supplier<T> action) {
        DataSource ds = dataSourceRegistry.get(dataSourceName);
        return executeWithDataSource(ds, action);
    }

    /**
     * 在默认数据源的事务中执行无返回值操作
     */
    public void executeVoid(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 在指定数据源的事务中执行无返回值操作
     */
    public void executeVoid(String dataSourceName, Runnable action) {
        execute(dataSourceName, () -> {
            action.run();
            return null;
        });
    }

    private <T> T executeWithDataSource(DataSource dataSource, Supplier<T> action) {
        // Bug-4修复：嵌套事务时校验是否为同一数据源，防止数据静默写入错误分库
        if (TransactionContext.isActive()) {
            DataSource activeDatasource = TransactionContext.currentDataSource();
            if (activeDatasource != null && activeDatasource != dataSource) {
                throw new GameJpaException(
                        "Nested transaction on a different DataSource is not supported. " +
                                "Cross-datasource operations must be handled in separate transactions.");
            }
            return action.get();
        }

        Connection conn = null;
        Boolean originalAutoCommit = null;
        try {
            conn = dataSource.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            TransactionContext.bind(conn, dataSource);

            T result = action.get();

            conn.commit();
            return result;
        } catch (Throwable e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (Exception re) {
                    /* ignore */ }
            }
            if (e instanceof GameJpaException gameJpaException) {
                throw gameJpaException;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new GameJpaException("Transaction failed", e);
        } finally {
            TransactionContext.clear();
            if (conn != null) {
                try {
                    if (originalAutoCommit != null) {
                        conn.setAutoCommit(originalAutoCommit);
                    }
                    conn.close();
                } catch (Exception e) {
                    /* ignore */ }
            }
        }
    }
}
