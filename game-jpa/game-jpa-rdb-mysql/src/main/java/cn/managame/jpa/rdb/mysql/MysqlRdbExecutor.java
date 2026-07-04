package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.exception.ConnectionException;
import cn.managame.jpa.core.exception.DuplicateKeyException;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.exception.OptimisticLockException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.converter.TypeConverterAware;
import cn.managame.jpa.core.converter.TypeConverterRegistry;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.metadata.ReflectionUtils;
import cn.managame.jpa.core.registry.DataSourceCatalog;
import cn.managame.jpa.core.registry.DataSourceRegistry;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbDefaultValues;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbFieldMetadata;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import cn.managame.jpa.rdb.transaction.TransactionContext;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import javax.sql.DataSource;
import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL RDB 执行器实现。
 * 无类级泛型，同一实例服务所有 RDB 实体。
 * 支持事务上下文、FieldAccessor 高性能字段访问、乐观锁 version 自增。
 */
public class MysqlRdbExecutor implements RdbExecutor, Closeable, TypeConverterAware, DataSourceCatalog {

    private static final Logger log = LoggerFactory.getLogger(MysqlRdbExecutor.class);

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DATA_TOO_LONG = 1406;
    private static final int MYSQL_NO_SUCH_TABLE = 1146;
    private static final Pattern DATA_TOO_LONG_COLUMN =
            Pattern.compile("Data too long for column '([^']+)'", Pattern.CASE_INSENSITIVE);

    private final DataSourceRegistry<DataSource> dataSourceRegistry;
    private final MysqlDialect dialect;
    private volatile TypeConverterRegistry converterRegistry;
    private volatile ObjectMapper json;
    /** 每字段缓存 JSON 反序列化器，避免每行重建 JavaType/Reader（读热点）。随 mapper 替换失效。 */
    private final ConcurrentHashMap<RdbFieldMetadata, ObjectReader> jsonReaders = new ConcurrentHashMap<>();
    /** 字段超长(Data too long)时自动 ALTER MODIFY 加宽到声明长度两倍再重写。默认开启。 */
    private volatile boolean columnAutoWiden = true;
    /** 已自动加宽的列(table:column -&gt; 已加宽到的长度)，去重避免并发重复 ALTER。 */
    private final ConcurrentHashMap<String, Integer> widenedColumns = new ConcurrentHashMap<>();
    /** 写入时物理表不存在(分表场景)则按元数据自动建表再重写。默认开启。 */
    private volatile boolean autoCreateShardTable = true;
    /** 已自动建过的物理表，去重避免并发重复建表/建索引。 */
    private final java.util.Set<String> createdTables = ConcurrentHashMap.newKeySet();

    public MysqlRdbExecutor(DataSource dataSource) {
        this(dataSource, new TypeConverterRegistry(), null);
    }

    public MysqlRdbExecutor(DataSource dataSource, TypeConverterRegistry converterRegistry) {
        this(dataSource, converterRegistry, null);
    }

    public MysqlRdbExecutor(DataSource dataSource, ObjectMapper json) {
        this(dataSource, new TypeConverterRegistry(), json);
    }

    public MysqlRdbExecutor(DataSource dataSource, TypeConverterRegistry converterRegistry, ObjectMapper json) {
        DataSourceRegistry<DataSource> registry = new DataSourceRegistry<>();
        registry.registerDefault(dataSource);
        this.dataSourceRegistry = registry;
        this.dialect = new MysqlDialect();
        this.converterRegistry = converterRegistry != null ? converterRegistry : new TypeConverterRegistry();
        this.json = json != null ? configureObjectMapper(json) : newObjectMapper();
    }

    public MysqlRdbExecutor(DataSourceRegistry<DataSource> dataSourceRegistry) {
        this(dataSourceRegistry, new TypeConverterRegistry(), null);
    }

    public MysqlRdbExecutor(DataSourceRegistry<DataSource> dataSourceRegistry,
            TypeConverterRegistry converterRegistry, ObjectMapper json) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.dialect = new MysqlDialect();
        this.converterRegistry = converterRegistry != null ? converterRegistry : new TypeConverterRegistry();
        this.json = json != null ? configureObjectMapper(json) : newObjectMapper();
    }

    @Override
    public void setTypeConverterRegistry(TypeConverterRegistry registry) {
        this.converterRegistry = registry != null ? registry : new TypeConverterRegistry();
    }

    @Override
    public java.util.Set<String> dataSourceNames() {
        return dataSourceRegistry.names();
    }

    public MysqlRdbExecutor objectMapper(ObjectMapper mapper) {
        this.json = mapper != null ? configureObjectMapper(mapper) : newObjectMapper();
        jsonReaders.clear();
        return this;
    }

    /**
     * 字段超长自动加宽开关。生产环境若不接受写路径自动 ALTER TABLE，可关闭（关闭后字段超长按确定性失败丢弃）。
     */
    public MysqlRdbExecutor columnAutoWiden(boolean enabled) {
        this.columnAutoWiden = enabled;
        return this;
    }

    /**
     * 分表缺失自动建表开关。生产环境若不接受写路径自动 CREATE TABLE，可关闭（关闭后写到不存在的物理表按确定性失败丢弃）。
     * 应用账号需具备建表权限；无权限时自动建表失败、写入照常丢弃。
     */
    public MysqlRdbExecutor autoCreateShardTable(boolean enabled) {
        this.autoCreateShardTable = enabled;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T findById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
        String sql = dialect.selectById(metadata, resolveTableName(metadata, context));
        return inStatement("findById", metadata, context, sql, ps -> {
            ps.setObject(1, converterRegistry.write(id));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (T) mapRow(metadata, rs) : null;
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(RdbEntityMetadata metadata, ExecutorContext context) {
        String sql = dialect.selectAll(metadata, resolveTableName(metadata, context));
        return inStatement("findAll", metadata, context, sql, ps -> {
            try (ResultSet rs = ps.executeQuery()) {
                List<Object> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(metadata, rs));
                }
                return (List<T>) result;
            }
        });
    }

    @Override
    public void insert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        RdbDefaultValues.applyInsertDefaults(metadata, entity);
        String tableName = resolveTableName(metadata, context);
        String sql = dialect.insert(metadata, tableName);
        try (Connection conn = getConnection(context);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            bindAllFields(metadata, entity, ps, true);
            ps.executeUpdate();
        } catch (GameJpaException e) {
            throw e;
        } catch (SQLException e) {
            throw wrapInsertException(metadata, entity, e);
        } catch (Exception e) {
            throw new GameJpaException("insert failed: " + metadata.tableName(), e);
        }
    }

    @Override
    public void update(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        String sql = dialect.update(metadata, resolveTableName(metadata, context));
        inStatement("update", metadata, context, sql, ps -> {
            bindUpdateFields(metadata, entity, ps);
            int rows = ps.executeUpdate();
            if (rows == 0 && metadata.hasVersion()) {
                Object entityId = metadata.idField().accessor().get(entity);
                throw new OptimisticLockException(metadata.tableName(), entityId);
            }
            if (metadata.hasVersion()) {
                incrementEntityVersion(metadata, entity);
            }
            return null;
        });
    }

    @Override
    public void upsert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        RdbDefaultValues.applyInsertDefaults(metadata, entity);
        String sql = dialect.upsert(metadata, resolveTableName(metadata, context));
        inStatement("upsert", metadata, context, sql, ps -> {
            // Bind INSERT values first, then UPDATE values used by ON DUPLICATE KEY UPDATE.
            int idx = bindAllFields(metadata, entity, ps, true);
            bindUpsertUpdateFields(metadata, entity, ps, idx);
            int rows = ps.executeUpdate();
            applyVersionedUpsertResult(metadata, entity, rows);
            return null;
        });
    }

    @Override
    public void deleteById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
        String tableName = resolveTableName(metadata, context);
        String sql = dialect.deleteById(metadata, tableName);
        try (Connection conn = getConnection(context);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, converterRegistry.write(id));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrapSqlException("deleteById", metadata, e);
        } catch (Exception e) {
            throw new GameJpaException("deleteById failed: " + metadata.tableName(), e);
        }
    }

    @Override
    public void batchDelete(RdbEntityMetadata metadata, List<?> ids, ExecutorContext context) {
        if (ids.isEmpty())
            return;
        String tableName = resolveTableName(metadata, context);
        String sql = dialect.deleteById(metadata, tableName); // 复用对应的 SQL 模板
        try (Connection conn = getConnection(context);
                BatchTransaction tx = beginBatchTransaction(conn);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object id : ids) {
                ps.setObject(1, converterRegistry.write(id));
                ps.addBatch();
            }
            ps.executeBatch();
            tx.commit();
        } catch (SQLException e) {
            throw wrapSqlException("batchDelete", metadata, e);
        } catch (Exception e) {
            throw new GameJpaException("batchDelete failed: " + metadata.tableName(), e);
        }
    }

    @Override
    public void batchInsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        batchInsert(metadata, entities, context, autoCreateShardTable, true);
    }

    private void batchInsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context,
            boolean autoCreateAllowed, boolean autoWidenAllowed) {
        if (entities.isEmpty())
            return;
        String tableName = resolveTableName(metadata, context);
        String sql = dialect.insert(metadata, tableName);
        try (Connection conn = getConnection(context);
            BatchTransaction tx = beginBatchTransaction(conn);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object entity : entities) {
                RdbDefaultValues.applyInsertDefaults(metadata, entity);
                bindAllFields(metadata, entity, ps, true);
                ps.addBatch();
            }
            ps.executeBatch();
            tx.commit();
        } catch (SQLException e) {
            if (autoCreateAllowed && tryAutoCreateTable(metadata, context, e)) {
                batchInsert(metadata, entities, context, false, autoWidenAllowed);
                return;
            }
            if (autoWidenAllowed && tryAutoWiden(metadata, context, e)) {
                batchInsert(metadata, entities, context, autoCreateAllowed, false);
                return;
            }
            throw wrapSqlException("batchInsert", metadata, e);
        } catch (Exception e) {
            throw new GameJpaException("batchInsert failed: " + metadata.tableName(), e);
        }
    }

    @Override
    public void batchUpdate(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        if (entities.isEmpty())
            return;
        String tableName = resolveTableName(metadata, context);
        String sql = dialect.update(metadata, tableName);
        try (Connection conn = getConnection(context);
                BatchTransaction tx = beginBatchTransaction(conn);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            if (metadata.hasVersion()) {
                batchVersionedUpdate(metadata, entities, ps, tx);
                return;
            }
            for (Object entity : entities) {
                bindUpdateFields(metadata, entity, ps);
                ps.addBatch();
            }
            ps.executeBatch();
            tx.commit();
        } catch (GameJpaException e) {
            throw e;
        } catch (SQLException e) {
            throw wrapSqlException("batchUpdate", metadata, e);
        } catch (Exception e) {
            throw new GameJpaException("batchUpdate failed: " + metadata.tableName(), e);
        }
    }

    /**
     * 版本化批量更新逐条执行 executeUpdate，以拿到可靠的 affectedRows 判定乐观锁冲突。
     * executeBatch 在 rewriteBatchedStatements=true（数据源默认开启）下可能对每个元素返回
     * SUCCESS_NO_INFO(-2)，无法用于版本冲突检测；带 @Version 的实体因此走逐条路径，
     * 与 {@link #batchVersionedUpsert} 保持一致。版本号 +1 延后到 commit 之后，
     * 回滚时内存版本不会超前于库。游戏服多数表不需要 @Version，此路径仅用于实时保存类数据。
     */
    private void batchVersionedUpdate(RdbEntityMetadata metadata, List<?> entities,
            PreparedStatement ps, BatchTransaction tx) throws Exception {
        for (Object entity : entities) {
            bindUpdateFields(metadata, entity, ps);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                Object entityId = metadata.idField().accessor().get(entity);
                throw new OptimisticLockException(metadata.tableName(), entityId);
            }
        }
        tx.commit();
        for (Object entity : entities) {
            incrementEntityVersion(metadata, entity);
        }
    }

    @Override
    public void batchUpsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        batchUpsert(metadata, entities, context, autoCreateShardTable, true);
    }

    private void batchUpsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context,
            boolean autoCreateAllowed, boolean autoWidenAllowed) {
        if (entities.isEmpty())
            return;
        String tableName = resolveTableName(metadata, context);
        String sql = dialect.upsert(metadata, tableName);
        try (Connection conn = getConnection(context);
                BatchTransaction tx = beginBatchTransaction(conn);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            if (metadata.hasVersion()) {
                batchVersionedUpsert(metadata, entities, ps, tx);
                return;
            }
            for (Object entity : entities) {
                RdbDefaultValues.applyInsertDefaults(metadata, entity);
                int idx = bindAllFields(metadata, entity, ps, true);
                bindUpsertUpdateFields(metadata, entity, ps, idx);
                ps.addBatch();
            }
            ps.executeBatch();
            tx.commit();
        } catch (GameJpaException e) {
            throw e;
        } catch (SQLException e) {
            if (autoCreateAllowed && tryAutoCreateTable(metadata, context, e)) {
                batchUpsert(metadata, entities, context, false, autoWidenAllowed);
                return;
            }
            if (autoWidenAllowed && tryAutoWiden(metadata, context, e)) {
                batchUpsert(metadata, entities, context, autoCreateAllowed, false);
                return;
            }
            throw wrapSqlException("batchUpsert", metadata, e);
        } catch (Exception e) {
            throw new GameJpaException("batchUpsert failed: " + metadata.tableName(), e);
        }
    }

    private void batchVersionedUpsert(RdbEntityMetadata metadata, List<?> entities,
            PreparedStatement ps, BatchTransaction tx) throws Exception {
        List<Object> updatedEntities = new ArrayList<>();
        for (Object entity : entities) {
            RdbDefaultValues.applyInsertDefaults(metadata, entity);
            int idx = bindAllFields(metadata, entity, ps, true);
            bindUpsertUpdateFields(metadata, entity, ps, idx);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                Object entityId = metadata.idField().accessor().get(entity);
                throw new OptimisticLockException(metadata.tableName(), entityId);
            }
            if (rows != 1) {
                updatedEntities.add(entity);
            }
        }
        tx.commit();
        for (Object entity : updatedEntities) {
            incrementEntityVersion(metadata, entity);
        }
    }

    private void applyVersionedUpsertResult(RdbEntityMetadata metadata, Object entity, int rows) {
        if (!metadata.hasVersion()) {
            return;
        }
        if (rows == 0) {
            Object entityId = metadata.idField().accessor().get(entity);
            throw new OptimisticLockException(metadata.tableName(), entityId);
        }
        // MySQL ON DUPLICATE KEY UPDATE: 1 usually means insert, 2 means update.
        // The update path increments the stored version, so mirror it in memory.
        if (rows != 1) {
            incrementEntityVersion(metadata, entity);
        }
    }

    private void incrementEntityVersion(RdbEntityMetadata metadata, Object entity) {
        long currentVersion = ((Number) metadata.versionField().accessor().get(entity)).longValue();
        metadata.versionField().accessor().set(entity, currentVersion + 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
        String sql = dialect.buildQuery(metadata, querySpec, resolveTableName(metadata, context));
        return inStatement("query", metadata, context, sql, ps -> {
            bindQueryParams(querySpec, ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<Object> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(metadata, rs));
                }
                return (List<T>) result;
            }
        });
    }

    @Override
    public long count(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
        String sql = dialect.buildCount(metadata, querySpec, resolveTableName(metadata, context));
        return inStatement("count", metadata, context, sql, ps -> {
            bindQueryParams(querySpec, ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    // ---- 内部方法 ----

    /**
     * 获取连接：优先使用事务上下文中的连接，否则从 DataSource 获取新连接。
     * 事务连接通过 JDK Proxy 包装，close() 为空操作，由 TransactionTemplate 管理生命周期。
     */
    private Connection getConnection(ExecutorContext context) throws SQLException {
        ExecutorContext actualContext = context != null ? context : ExecutorContext.defaultContext();
        DataSource dataSource = dataSourceRegistry.get(actualContext.dataSourceName());
        Connection txConn = TransactionContext.current();
        if (txConn != null) {
            DataSource activeDataSource = TransactionContext.currentDataSource();
            if (activeDataSource != null && activeDataSource != dataSource) {
                throw new GameJpaException("Active transaction DataSource does not match routed DataSource: "
                        + actualContext.dataSourceName());
            }
            return createNonClosingProxy(txConn);
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new ConnectionException("Failed to get connection from DataSource",
                    actualContext.dataSourceName(), e);
        }
    }

    /**
     * 使用 JDK Proxy 创建不关闭的连接代理，替代手动委托 50+ 方法的 Wrapper 类。
     */
    private static Connection createNonClosingProxy(Connection target) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
                return null; // 不关闭，由事务管理
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                handler);
    }

    /**
     * 单条 PreparedStatement 操作的执行模板：统一获取连接、关闭资源与异常包装链。
     * 仅用于 catch 链一致的方法（findById/findAll/update/upsert/query/count），
     * insert（主键冲突识别）、deleteById（不同 catch）与批量方法（事务）保留各自实现。
     */
    @FunctionalInterface
    private interface SqlWork<R> {
        R apply(PreparedStatement ps) throws Exception;
    }

    private <R> R inStatement(String operation, RdbEntityMetadata metadata,
            ExecutorContext context, String sql, SqlWork<R> work) {
        try (Connection conn = getConnection(context);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            return work.apply(ps);
        } catch (GameJpaException e) {
            throw e;
        } catch (SQLException e) {
            throw wrapSqlException(operation, metadata, e);
        } catch (Exception e) {
            throw new GameJpaException(operation + " failed: " + metadata.tableName(), e);
        }
    }

    private Object mapRow(RdbEntityMetadata metadata, ResultSet rs) throws Exception {
        Object instance = ReflectionUtils.newInstance(metadata.entityType());
        // SELECT projects columns in metadata.fields() order (see MysqlDialect), so read
        // by column index to skip the per-cell, per-row column-name lookup in the driver.
        List<RdbFieldMetadata> fields = metadata.fields();
        for (int i = 0; i < fields.size(); i++) {
            RdbFieldMetadata field = fields.get(i);
            Object value = rs.getObject(i + 1);
            if (value != null) {
                field.accessor().set(instance, toJavaValue(field, value));
            }
        }
        return instance;
    }

    private int bindAllFields(RdbEntityMetadata metadata, Object entity, PreparedStatement ps,
            boolean applyDefaults) throws Exception {
        int idx = 1;
        for (RdbFieldMetadata field : metadata.fields()) {
            Object value = field.accessor().get(entity);
            ps.setObject(idx++, toStorageValue(field, value, applyDefaults));
        }
        return idx;
    }

    /**
     * 绑定 UPDATE 语句参数：非主键字段（version 字段自增）→ 主键 WHERE → version WHERE。
     * 供 {@link #update} 与 {@link #batchUpdate} 共用，消除两处逐行重复的绑定逻辑。
     */
    private void bindUpdateFields(RdbEntityMetadata metadata, Object entity, PreparedStatement ps) throws Exception {
        int idx = 1;
        for (RdbFieldMetadata field : metadata.nonIdFields()) {
            if (field.isVersionField()) {
                long currentVersion = ((Number) field.accessor().get(entity)).longValue();
                ps.setObject(idx++, currentVersion + 1);
            } else {
                ps.setObject(idx++, toStorageValue(field, field.accessor().get(entity), false));
            }
        }
        ps.setObject(idx++, converterRegistry.write(metadata.idField().accessor().get(entity)));
        if (metadata.hasVersion()) {
            ps.setObject(idx, converterRegistry.write(metadata.versionField().accessor().get(entity)));
        }
    }

    private int bindUpsertUpdateFields(RdbEntityMetadata metadata, Object entity,
            PreparedStatement ps, int idx) throws Exception {
        // 绑定顺序必须与 MysqlDialect.buildUpsert 的子句顺序一致：先所有非 version 列
        // （每列前面是它的 version 比较参数），最后是 version 自增子句的比较参数。
        boolean hasVersion = metadata.hasVersion();
        Object versionCompare = hasVersion
                ? converterRegistry.write(metadata.versionField().accessor().get(entity))
                : null;
        for (RdbFieldMetadata field : metadata.nonIdFields()) {
            if (field.isVersionField()) {
                continue;
            }
            if (hasVersion) {
                ps.setObject(idx++, versionCompare);
            }
            ps.setObject(idx++, toStorageValue(field, field.accessor().get(entity), false));
        }
        if (hasVersion) {
            ps.setObject(idx++, versionCompare);
        }
        return idx;
    }

    /**
     * 将 Java 值转为数据库存储值。JSON 字段序列化为字符串，其他原样传递。
     */
    private Object toStorageValue(RdbFieldMetadata field, Object value, boolean applyDefaults) {
        Object actualValue = value;
        if (actualValue == null)
            return null;
        if (field.isJsonField()) {
            try {
                return json.writeValueAsString(actualValue);
            } catch (JsonProcessingException e) {
                throw new GameJpaException("JSON serialize failed for field: "
                        + field.propertyName(), e);
            }
        }
        return converterRegistry.write(actualValue);
    }

    /**
     * 将数据库值转为 Java 值。JSON 字段从字符串反序列化为目标类型，其他原样传递。
     */
    private Object toJavaValue(RdbFieldMetadata field, Object value) {
        if (value == null)
            return null;
        if (field.isJsonField()) {
            try {
                return jsonReaderFor(field).readValue(value.toString());
            } catch (JsonProcessingException e) {
                throw new GameJpaException("JSON deserialize failed for field: "
                        + field.propertyName(), e);
            }
        }
        return converterRegistry.read(value, field.javaType());
    }

    /**
     * 取（并缓存）字段的 JSON 反序列化器。读热点路径避免每行重建 JavaType。
     */
    private ObjectReader jsonReaderFor(RdbFieldMetadata field) {
        return jsonReaders.computeIfAbsent(field,
                f -> json.readerFor(json.getTypeFactory().constructType(f.javaField().getGenericType())));
    }

    private static ObjectMapper newObjectMapper() {
        return configureObjectMapper(new ObjectMapper());
    }

    private static ObjectMapper configureObjectMapper(ObjectMapper mapper) {
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /**
     * 解析实际表名：优先使用 ExecutorContext 中的物理表名（分表），否则使用元数据中的逻辑表名。
     */
    private String resolveTableName(RdbEntityMetadata metadata, ExecutorContext context) {
        ExecutorContext actualContext = context != null ? context : ExecutorContext.defaultContext();
        String physicalName = actualContext.physicalTableName();
        return physicalName != null ? physicalName : metadata.tableName();
    }

    private void bindQueryParams(RdbQuerySpec spec, PreparedStatement ps) throws SQLException {
        int idx = 1;
        for (RdbQuerySpec.Condition cond : spec.conditions()) {
            if (cond.operator() == RdbQuerySpec.Operator.IN && cond.value() instanceof Collection<?> col) {
                for (Object v : col) {
                    ps.setObject(idx++, converterRegistry.write(v));
                }
            } else {
                ps.setObject(idx++, converterRegistry.write(cond.value()));
            }
        }
    }

    /**
     * 包装 insert 的 SQLException，识别主键冲突。
     */
    private BatchTransaction beginBatchTransaction(Connection conn) throws SQLException {
        boolean externalTransaction = TransactionContext.isActive();
        boolean originalAutoCommit = conn.getAutoCommit();
        Savepoint savepoint = null;
        if (externalTransaction) {
            savepoint = conn.setSavepoint();
        } else if (originalAutoCommit) {
            conn.setAutoCommit(false);
        }
        return new BatchTransaction(conn, externalTransaction, originalAutoCommit, savepoint);
    }

    private static final class BatchTransaction implements AutoCloseable {
        private final Connection conn;
        private final boolean externalTransaction;
        private final boolean originalAutoCommit;
        private final Savepoint savepoint;
        private boolean committed;

        private BatchTransaction(Connection conn, boolean externalTransaction,
                boolean originalAutoCommit, Savepoint savepoint) {
            this.conn = conn;
            this.externalTransaction = externalTransaction;
            this.originalAutoCommit = originalAutoCommit;
            this.savepoint = savepoint;
        }

        private void commit() throws SQLException {
            if (externalTransaction) {
                if (savepoint != null) {
                    conn.releaseSavepoint(savepoint);
                }
            } else {
                conn.commit();
            }
            committed = true;
        }

        @Override
        public void close() throws SQLException {
            try {
                if (!committed) {
                    if (externalTransaction && savepoint != null) {
                        conn.rollback(savepoint);
                    } else {
                        conn.rollback();
                    }
                }
            } finally {
                if (!externalTransaction && conn.getAutoCommit() != originalAutoCommit) {
                    conn.setAutoCommit(originalAutoCommit);
                }
            }
        }
    }

    private GameJpaException wrapInsertException(RdbEntityMetadata metadata, Object entity, SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException
                || e.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
            Object id = metadata.idField().accessor().get(entity);
            return new DuplicateKeyException(metadata.tableName(), id, e);
        }
        return wrapSqlException("insert", metadata, e);
    }

    /**
     * 通用 SQLException 包装，识别连接类异常。
     */
    private GameJpaException wrapSqlException(String operation, RdbEntityMetadata metadata, SQLException e) {
        String sqlState = e.getSQLState();
        int errorCode = e.getErrorCode();
        // SQLState 08xxx = connection exception（可重试）
        if (sqlState != null && sqlState.startsWith("08")) {
            return new ConnectionException(
                    operation + " failed (connection error): " + metadata.tableName(), "default", e);
        }
        // 死锁 / 锁等待超时：瞬时冲突，重试通常能成功
        if (errorCode == MYSQL_DEADLOCK || errorCode == MYSQL_LOCK_WAIT_TIMEOUT
                || "40001".equals(sqlState)) {
            return new RetriableWriteException(
                    operation + " failed (transient lock): " + metadata.tableName(), e);
        }
        return new GameJpaException(operation + " failed: " + metadata.tableName(), e);
    }

    // ==================== 分表缺失自愈 ====================

    /**
     * 物理表不存在(No such table, 1146)自愈：按元数据 CREATE TABLE 物理表 → 返回 true 让调用方重写一次。
     * 仅当开关开启、错误码为 1146 时尝试；{@link #createdTables} + {@code synchronized} 去重，避免并发重复建表；
     * 建表失败（如应用账号无 DDL 权限）返回 false，写入按确定性失败丢弃。
     */
    private boolean tryAutoCreateTable(RdbEntityMetadata metadata, ExecutorContext context, SQLException e) {
        if (!autoCreateShardTable || e.getErrorCode() != MYSQL_NO_SUCH_TABLE) {
            return false;
        }
        String table = resolveTableName(metadata, context);
        if (createdTables.contains(table)) {
            return true;
        }
        synchronized (createdTables) {
            if (createdTables.contains(table)) {
                return true;
            }
            try {
                DataSource dataSource = dataSourceRegistry.get(dataSourceName(context));
                new MysqlSchemaGenerator(dataSource, json).createTable(metadata, table);
                createdTables.add(table);
                log.warn("[{}] auto-created missing shard table (按需建物理表)", table);
                return true;
            } catch (RuntimeException ce) {
                log.warn("[{}] auto-create shard table failed; dropping write", table, ce);
                return false;
            }
        }
    }

    // ==================== 字段超长自愈 ====================

    /**
     * 字段超长(Data too long, 1406)自愈：解析超长列 → ALTER MODIFY 到声明长度两倍 → 返回 true 让调用方重写一次。
     * 仅当开关开启、错误码为 1406、能解析出可加宽的列时才尝试；数据若超过两倍仍会失败并最终按确定性失败丢弃。
     */
    private boolean tryAutoWiden(RdbEntityMetadata metadata, ExecutorContext context, SQLException e) {
        if (!columnAutoWiden || e.getErrorCode() != MYSQL_DATA_TOO_LONG) {
            return false;
        }
        String column = parseDataTooLongColumn(e.getMessage());
        if (column == null) {
            return false;
        }
        RdbFieldMetadata field = findWidenableField(metadata, column);
        if (field == null) {
            return false;
        }
        return widenColumn(resolveTableName(metadata, context), field, context);
    }

    static String parseDataTooLongColumn(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = DATA_TOO_LONG_COLUMN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static RdbFieldMetadata findWidenableField(RdbEntityMetadata metadata, String column) {
        for (RdbFieldMetadata field : metadata.fields()) {
            if (field.columnName().equalsIgnoreCase(column) && isWidenable(field)) {
                return field;
            }
        }
        return null;
    }

    /** 仅可变长字符 / 二进制列（带 {@code (length)}）可加宽；JSON / TEXT / 数值列无长度概念，不处理。 */
    static boolean isWidenable(RdbFieldMetadata field) {
        if (field.isJsonField()) {
            return false;
        }
        String type = MysqlTypeMapping.toSqlType(field.javaType(), field.length(), false, field.sqlType())
                .toUpperCase(Locale.ROOT);
        return type.startsWith("VARCHAR(") || type.startsWith("CHAR(")
                || type.startsWith("VARBINARY(") || type.startsWith("BINARY(");
    }

    /**
     * 执行 ALTER MODIFY 把列加宽到声明长度两倍。{@code synchronized} + {@link #widenedColumns} 去重，
     * 避免并发分支对同一列重复 ALTER；已加宽到足够长时直接返回 true 让调用方重写。
     */
    private boolean widenColumn(String table, RdbFieldMetadata field, ExecutorContext context) {
        int newLength = field.length() * 2;
        String key = table + ":" + field.columnName();
        Integer widened = widenedColumns.get(key);
        if (widened != null && widened >= newLength) {
            return true;
        }
        synchronized (widenedColumns) {
            widened = widenedColumns.get(key);
            if (widened != null && widened >= newLength) {
                return true;
            }
            String newType = MysqlTypeMapping.toSqlType(field.javaType(), newLength, false, field.sqlType());
            String sql = "ALTER TABLE " + MysqlDialect.quoteIdentifier(table)
                    + " MODIFY COLUMN " + MysqlDialect.quoteIdentifier(field.columnName()) + " " + newType;
            DataSource dataSource = dataSourceRegistry.get(dataSourceName(context));
            try (Connection conn = dataSource.getConnection();
                    Statement st = conn.createStatement()) {
                log.warn("[{}] auto-widen column '{}' -> {} (declared length {} doubled after Data too long)",
                        table, field.columnName(), newType, field.length());
                st.execute(sql);
                widenedColumns.put(key, newLength);
                return true;
            } catch (SQLException alterEx) {
                log.warn("[{}] auto-widen ALTER MODIFY column '{}' failed; dropping write",
                        table, field.columnName(), alterEx);
                return false;
            }
        }
    }

    private static String dataSourceName(ExecutorContext context) {
        ExecutorContext actual = context != null ? context : ExecutorContext.defaultContext();
        return actual.dataSourceName();
    }

    @Override
    public void close() {
        for (DataSource dataSource : dataSourceRegistry.values()) {
            if (dataSource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    throw new GameJpaException("Failed to close DataSource", e);
                }
            }
        }
    }
}
