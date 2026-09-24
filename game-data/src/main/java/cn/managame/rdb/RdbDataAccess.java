package cn.managame.rdb;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.access.Query;
import cn.managame.core.mapping.EntityMapper;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.PropertyMetadata;
import cn.managame.core.write.BatchResult;
import cn.managame.core.write.BatchItemResult;
import cn.managame.core.write.BatchItemState;
import cn.managame.core.write.WriteFailureAction;
import cn.managame.core.write.WriteOperation;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Shared JDBC implementation for relational databases. Database differences live in RdbDialect. */
public final class RdbDataAccess implements DataAccess {
    private final DataSource dataSource;
    private final RdbDialect dialect;
    private final EntityMapper mapper;
    private final int maxBatchSize;
    private final int scanFetchSize;
    private final RdbSchemaManager schemaManager;
    private final Map<Class<?>, RdbEntityPlan> plans = new ConcurrentHashMap<>();

    public RdbDataAccess(DataSource dataSource, RdbDialect dialect) {
        this(dataSource, dialect, new EntityMapper(new RdbValueConverter()), 4_096, 512);
    }

    public RdbDataAccess(DataSource dataSource, RdbDialect dialect, EntityMapper mapper) {
        this(dataSource, dialect, mapper, 4_096, 512);
    }

    public RdbDataAccess(
            DataSource dataSource,
            RdbDialect dialect,
            EntityMapper mapper,
            int maxBatchSize,
            int scanFetchSize) {
        this(dataSource, dialect, mapper, maxBatchSize, scanFetchSize, RdbSchemaReporter.stderr());
    }

    public RdbDataAccess(
            DataSource dataSource,
            RdbDialect dialect,
            EntityMapper mapper,
            int maxBatchSize,
            int scanFetchSize,
            RdbSchemaReporter schemaReporter) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.dialect = Objects.requireNonNull(dialect);
        this.mapper = Objects.requireNonNull(mapper);
        if (maxBatchSize <= 0) throw new IllegalArgumentException("maxBatchSize must be > 0");
        if (scanFetchSize <= 0) throw new IllegalArgumentException("scanFetchSize must be > 0");
        this.maxBatchSize = maxBatchSize;
        this.scanFetchSize = scanFetchSize;
        this.schemaManager = new RdbSchemaManager(dataSource, dialect, Objects.requireNonNull(schemaReporter));
    }

    public DataSource dataSource() { return dataSource; }
    public RdbDialect dialect() { return dialect; }
    public EntityMapper mapper() { return mapper; }
    public RdbSchemaManager schemaManager() { return schemaManager; }

    @Override public void validateMapping(EntityMetadata<?> metadata) {
        mapper.validate(metadata);
        for (PropertyMetadata property : metadata.properties()) dialect.sqlType(property);
    }

    @Override
    public String writerKey(EntityMetadata<?> metadata, String physicalName) {
        String table = physicalName == null || physicalName.isBlank() ? metadata.rdbTable() : physicalName;
        String schema = metadata.rdbSchema().isBlank() ? "" : metadata.rdbSchema() + ".";
        return "rdb:" + schema + table;
    }

    @Override
    public <T, ID> Optional<T> findById(EntityMetadata<T> metadata, ID id) {
        RdbEntityPlan plan = plan(metadata);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(plan.selectById)) {
            dialect.bind(ps, 1, mapper.toStore(id, metadata.idProperty()), metadata.idProperty());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(metadata, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataException("RDB findById failed for " + metadata.type().getName()
                    + " via " + dialect.name(), e);
        }
    }

    @Override
    public <T> List<T> find(EntityMetadata<T> metadata, Query query) {
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(metadata.properties().stream()
                .map(p -> dialect.quote(p.rdbName()))
                .collect(Collectors.joining(",")));
        sql.append(" FROM ").append(dialect.qualifiedTable(metadata));

        List<Parameter> args = new ArrayList<>();
        appendWhere(metadata, query, sql, args);
        String finalSql = dialect.applyLimit(sql.toString(), query.limit());

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(finalSql)) {
            for (int i = 0; i < args.size(); i++) dialect.bind(ps, i + 1, args.get(i).value(), args.get(i).property());
            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) result.add(mapRow(metadata, rs));
                return result;
            }
        } catch (SQLException e) {
            throw new DataException("RDB query failed for " + metadata.type().getName()
                    + " via " + dialect.name(), e);
        }
    }

    @Override
    public <T> void scan(EntityMetadata<T> metadata, Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        String columns = metadata.properties().stream()
                .map(p -> dialect.quote(p.rdbName()))
                .collect(Collectors.joining(","));
        String sql = "SELECT " + columns + " FROM " + dialect.qualifiedTable(metadata);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            dialect.configureScan(c, ps, scanFetchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) consumer.accept(mapRow(metadata, rs));
            }
        } catch (SQLException e) {
            throw new DataException("RDB scan failed for " + metadata.type().getName()
                    + " via " + dialect.name(), e);
        }
    }

    private record Parameter(PropertyMetadata property, Object value) { }

    private void appendWhere(EntityMetadata<?> metadata, Query query, StringBuilder sql, List<Parameter> args) {
        if (query.criteria().isEmpty()) return;
        sql.append(" WHERE ");
        for (int i = 0; i < query.criteria().size(); i++) {
            if (i > 0) sql.append(" AND ");
            Query.Criterion c = query.criteria().get(i);
            PropertyMetadata property = metadata.property(c.property());
            String col = dialect.quote(property.rdbName());
            switch (c.operator()) {
                case EQ -> { sql.append(col).append("=?"); args.add(new Parameter(property, mapper.toStore(c.value(), property))); }
                case NE -> { sql.append(col).append("<>?"); args.add(new Parameter(property, mapper.toStore(c.value(), property))); }
                case GT -> { sql.append(col).append(">?"); args.add(new Parameter(property, mapper.toStore(c.value(), property))); }
                case GTE -> { sql.append(col).append(">=?"); args.add(new Parameter(property, mapper.toStore(c.value(), property))); }
                case LT -> { sql.append(col).append("<?"); args.add(new Parameter(property, mapper.toStore(c.value(), property))); }
                case LTE -> { sql.append(col).append("<=?"); args.add(new Parameter(property, mapper.toStore(c.value(), property))); }
                case IN -> {
                    if (!(c.value() instanceof Collection<?> values) || values.isEmpty()) {
                        sql.append("1=0");
                    } else {
                        sql.append(col).append(" IN (")
                                .append(values.stream().map(v -> "?").collect(Collectors.joining(",")))
                                .append(')');
                        for (Object value : values) args.add(new Parameter(property, mapper.toStore(value, property)));
                    }
                }
            }
        }
    }

    @Override
    public int maxBatchSize() { return maxBatchSize; }

    @Override
    public WriteFailureAction classifyWriteFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RdbBatchException batch) {
                return batch.retryable() ? WriteFailureAction.RETRY : WriteFailureAction.FAIL;
            }
        }
        return WriteFailureAction.FAIL;
    }

    private static boolean retryableTransactionFailure(Throwable error) {
        SQLException sql = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException found) { sql = found; break; }
        }
        if (sql == null) return false;
        if (sql instanceof SQLTransactionRollbackException) return true;
        String state = sql.getSQLState();
        return "40001".equals(state) || "40P01".equals(state);
    }

    @Override
    public BatchResult applyBatch(List<WriteOperation<?>> operations) {
        if (operations.isEmpty()) return BatchResult.success(0);
        EntityMetadata<?> metadata = operations.getFirst().metadata();
        String physicalName = operations.getFirst().physicalName();
        for (WriteOperation<?> operation : operations) {
            if (operation.metadata().type() != metadata.type()) {
                throw new DataException("RDB batch must contain one entity type only");
            }
            if (!Objects.equals(operation.physicalName(), physicalName)) {
                throw new DataException("RDB batch must contain one physical table only");
            }
        }

        Connection c = null;
        boolean oldAutoCommit = true;
        boolean commitStarted = false;
        boolean transactionStarted = false;
        boolean transactionResolved = false;
        boolean commitSucceeded = false;
        try {
            c = dataSource.getConnection();
            oldAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            transactionStarted = true;

            int i = 0;
            while (i < operations.size()) {
                WriteOperation<?> first = operations.get(i);
                int end = i + 1;
                while (end < operations.size() && sameStatementKey(first, operations.get(end))) end++;
                executeConsecutiveBatch(c, operations.subList(i, end), physicalName);
                i = end;
            }

            commitStarted = true;
            c.commit();
            commitSucceeded = true;
            transactionResolved = true;
            return BatchResult.success(operations.size());
        } catch (Throwable e) {
            if (c != null && transactionStarted) {
                try { c.rollback(); transactionResolved = true; }
                catch (SQLException rollback) { e.addSuppressed(rollback); }
            }
            boolean retryable = !commitStarted && transactionResolved && retryableTransactionFailure(e);
            BatchItemState state = !transactionStarted ? BatchItemState.UNEXECUTED
                    : commitStarted || !transactionResolved ? BatchItemState.UNKNOWN : BatchItemState.FAILED;
            List<BatchItemResult> problems = new ArrayList<>(operations.size());
            for (int i = 0; i < operations.size(); i++) {
                problems.add(new BatchItemResult(i, state, state == BatchItemState.UNKNOWN
                        ? "Transaction outcome unknown" : "Transaction did not persist this operation"));
            }
            throw new RdbBatchException("RDB batch failed via " + dialect.name(), retryable,
                    BatchResult.of(operations.size(), problems), e);
        } finally {
            if (c != null) releaseConnection(c, oldAutoCommit,
                    transactionResolved && (!commitStarted || commitSucceeded));
        }
    }

    private static void releaseConnection(Connection connection, boolean oldAutoCommit, boolean resolved) {
        if (resolved) {
            try { connection.setAutoCommit(oldAutoCommit); }
            catch (SQLException resetFailure) { resolved = false; }
        }
        if (!resolved) {
            // Never return a possibly live transaction to a pool or implicitly commit it by resetting auto-commit.
            try { connection.abort(Runnable::run); }
            catch (SQLException | RuntimeException abortFailure) {
                System.getLogger(RdbDataAccess.class.getName()).log(System.Logger.Level.ERROR,
                        "Cannot abort unresolved JDBC connection; it must not be returned to the pool", abortFailure);
                return;
            }
        }
        try { connection.close(); }
        catch (SQLException closeFailure) {
            System.getLogger(RdbDataAccess.class.getName()).log(System.Logger.Level.WARNING,
                    "Cannot close JDBC connection", closeFailure);
        }
    }

    private static boolean sameStatementKey(WriteOperation<?> a, WriteOperation<?> b) {
        return a.type() == b.type() && a.metadata().type() == b.metadata().type()
                && Objects.equals(a.physicalName(), b.physicalName());
    }

    private void executeConsecutiveBatch(Connection c, List<WriteOperation<?>> batch, String physicalName) throws SQLException {
        WriteOperation<?> first = batch.getFirst();
        // Dynamic log tables reuse the entity's INSERT shape. Only this batch retains the table SQL.
        RdbEntityPlan plan = physicalName == null || first.type() == cn.managame.core.write.WriteType.INSERT
                ? plan(first.metadata()) : new RdbEntityPlan(first.metadata(), dialect, physicalName);
        String sql = switch (first.type()) {
            case DELETE_INSERT -> throw new DataException("DELETE_INSERT must be expanded by the write buffer");
            case INSERT -> physicalName == null ? plan.insert
                    : plan.insertInto(dialect.qualifiedTable(first.metadata(), physicalName));
            case UPDATE -> plan.update;
            case DELETE -> plan.delete;
            case DELETE_GROUP -> {
                if (plan.deleteGroup == null) {
                    throw new DataException("DELETE_GROUP requires @GroupKey: " + first.metadata().type().getName());
                }
                yield plan.deleteGroup;
            }
        };
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (WriteOperation<?> op : batch) {
                bindOperation(ps, op);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            if (counts.length != batch.size()) throw new SQLException("JDBC batch returned an unexpected update-count length");
            for (int i = 0; i < counts.length; i++) validateWriteCount(c, batch.get(i), counts[i], physicalName);
        }
    }

    private void validateWriteCount(Connection connection, WriteOperation<?> op, int count, String physicalName) throws SQLException {
        if (count < 0 && count != Statement.SUCCESS_NO_INFO) {
            throw new SQLException("JDBC batch operation failed: " + op.type() + ", id=" + op.id());
        }
        switch (op.type()) {
            case INSERT -> {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("INSERT affected unexpected rows: " + count + ", id=" + op.id());
                }
            }
            case UPDATE -> {
                if (count > 1) throw new SQLException("UPDATE affected multiple rows: id=" + op.id());
                if (count == 0 || count == Statement.SUCCESS_NO_INFO) {
                    // Drivers may return changed rows (zero for an identical value) or no exact count.
                    // Only ambiguous updates pay for an existence check, on the same transaction.
                    String sql = "SELECT 1 FROM " + dialect.qualifiedTable(op.metadata(), physicalName)
                            + " WHERE " + dialect.quote(op.metadata().idProperty().rdbName()) + "=?";
                    try (PreparedStatement check = connection.prepareStatement(sql)) {
                        dialect.bind(check, 1, mapper.toStore(op.id(), op.metadata().idProperty()), op.metadata().idProperty());
                        try (ResultSet rows = check.executeQuery()) {
                            if (!rows.next()) throw new SQLException("UPDATE found no record: id=" + op.id());
                        }
                    }
                }
            }
            case DELETE -> {
                if (count > 1) throw new SQLException("DELETE affected multiple rows: id=" + op.id());
                // Zero is a successful idempotent delete.
            }
            case DELETE_GROUP -> { }
        }
    }

    private void bindOperation(PreparedStatement ps, WriteOperation<?> op) throws SQLException {
        EntityMetadata<?> m = op.metadata();
        int index = 1;
        switch (op.type()) {
            case INSERT -> {
                Object entity = op.entity();
                for (PropertyMetadata p : m.properties()) {
                    dialect.bind(ps, index++, mapper.toStore(p.get(entity), p), p);
                }
            }
            case UPDATE -> {
                Object entity = op.entity();
                for (PropertyMetadata p : m.properties()) {
                    if (!p.id()) dialect.bind(ps, index++, mapper.toStore(p.get(entity), p), p);
                }
                dialect.bind(ps, index, mapper.toStore(op.id(), m.idProperty()), m.idProperty());
            }
            case DELETE -> dialect.bind(ps, index, mapper.toStore(op.id(), m.idProperty()), m.idProperty());
            case DELETE_GROUP -> {
                List<Object> values = m.groupKeyValues(op.groupKey());
                for (int i = 0; i < m.groupKeyProperties().size(); i++) {
                    PropertyMetadata property = m.groupKeyProperties().get(i);
                    dialect.bind(ps, index++, mapper.toStore(values.get(i), property), property);
                }
            }
        }
    }

    @Override
    public void ensureSchema(EntityMetadata<?> metadata) {
        schemaManager.ensure(metadata);
        plan(metadata);
    }

    @Override
    public void ensureSchema(EntityMetadata<?> metadata, String physicalName) {
        schemaManager.ensure(metadata, physicalName);
        plan(metadata);
    }

    private <T> T mapRow(EntityMetadata<T> metadata, ResultSet rs) throws SQLException {
        T entity = metadata.newInstance();
        for (PropertyMetadata p : metadata.properties()) {
            p.set(entity, mapper.fromStore(p.storageKind() == cn.managame.core.metadata.StorageKind.SCALAR
                    && p.type() == java.time.LocalTime.class
                    ? rs.getObject(p.rdbName(), java.time.LocalTime.class) : rs.getObject(p.rdbName()), p));
        }
        return entity;
    }

    private RdbEntityPlan plan(EntityMetadata<?> metadata) {
        return plans.computeIfAbsent(metadata.type(), ignored -> new RdbEntityPlan(metadata, dialect));
    }
}
