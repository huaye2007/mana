package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.converter.TypeConverter;
import cn.managame.jpa.core.converter.TypeConverterRegistry;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.registry.DataSourceRegistry;
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.ColumnType;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.rdb.annotation.Id;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.annotation.Version;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.rdb.metadata.RdbFieldMetadata;
import cn.managame.jpa.rdb.transaction.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class MysqlRdbExecutorBatchTest {

    @Test
    public void batchInsertRollsBackWhenBatchFails() {
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);

        try {
            executor.batchInsert(metadata, List.of(new Player(1L)), ExecutorContext.defaultContext());
            fail("Expected batch failure");
        } catch (GameJpaException expected) {
            assertTrue(recording.rollbackCalled);
            assertFalse(recording.commitCalled);
            assertTrue(recording.autoCommitRestored);
        }
    }

    @Test
    public void batchDeleteRollsBackWhenBatchFails() {
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);

        try {
            executor.batchDelete(metadata, List.of(1L, 2L), ExecutorContext.defaultContext());
            fail("Expected batch failure");
        } catch (GameJpaException expected) {
            assertTrue(recording.rollbackCalled);
            assertFalse(recording.commitCalled);
            assertTrue(recording.autoCommitRestored);
        }
    }

    @Test
    public void findByIdAllowsNullExecutorContext() {
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);

        assertNull(executor.findById(metadata, 1L, null));
    }

    @Test
    public void transactionConnectionProxyUnwrapsSqlException() {
        RecordingConnection recording = new RecordingConnection();
        recording.prepareStatementFailure = new SQLException("prepare failed", "42000");
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        DataSourceRegistry<DataSource> registry = new DataSourceRegistry<>();
        registry.registerDefault(dataSource);
        TransactionTemplate template = new TransactionTemplate(registry);

        try {
            template.executeVoid(() -> executor.findById(metadata, 1L, ExecutorContext.defaultContext()));
            fail("Expected SQL failure");
        } catch (GameJpaException expected) {
            assertTrue(expected.getMessage().contains("findById"));
            assertSame(recording.prepareStatementFailure, expected.getCause());
        }

        assertTrue(recording.rollbackCalled);
        assertFalse(recording.commitCalled);
    }

    @Test
    public void upsertBindsInsertAndUpdateParameters() {
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(VersionedPlayer.class);

        executor.upsert(metadata, new VersionedPlayer(1L, "ada", 7L), ExecutorContext.defaultContext());

        assertEquals(List.of(1, 2, 3, 4, 5, 6), recording.setObjectIndices);
        assertEquals(List.of(1L, "ada", 7L, 7L, "ada", 7L), recording.setObjectValues);
    }

    @Test
    public void upsertThrowsOptimisticLockWhenVersionDoesNotMatch() {
        RecordingConnection recording = new RecordingConnection();
        recording.executeUpdateResult = 0;
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(VersionedPlayer.class);

        assertThrows(cn.managame.jpa.core.exception.OptimisticLockException.class, () -> {
            executor.upsert(metadata, new VersionedPlayer(1L, "ada", 7L), ExecutorContext.defaultContext());
        });
    }

    @Test
    public void versionedBatchUpdateDetectsConflictViaPerRowExecuteUpdate() {
        // 带 @Version 的 batchUpdate 走逐条 executeUpdate（rewriteBatchedStatements 下 executeBatch
        // 的返回值不可靠）。executeUpdate 返回 0 表示版本不匹配，应抛乐观锁异常并回滚。
        RecordingConnection recording = new RecordingConnection();
        recording.executeUpdateResult = 0;
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(VersionedPlayer.class);

        assertThrows(cn.managame.jpa.core.exception.OptimisticLockException.class, () ->
                executor.batchUpdate(metadata, List.of(new VersionedPlayer(1L, "ada", 7L)),
                        ExecutorContext.defaultContext()));
        assertTrue(recording.rollbackCalled);
        assertFalse(recording.commitCalled);
    }

    @Test
    public void versionedBatchUpdateCommitsAndBumpsVersionWhenRowsMatch() {
        RecordingConnection recording = new RecordingConnection();
        recording.executeUpdateResult = 1;
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(VersionedPlayer.class);
        VersionedPlayer player = new VersionedPlayer(1L, "ada", 7L);

        executor.batchUpdate(metadata, List.of(player), ExecutorContext.defaultContext());

        assertTrue(recording.commitCalled);
        assertFalse(recording.rollbackCalled);
        assertEquals(8L, player.version);
    }

    @Test
    public void insertSerializesJsonPrivateObjectField() {
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PlayerWithProfile.class);

        executor.insert(metadata, new PlayerWithProfile(1L, new Profile(7, "warrior")),
                ExecutorContext.defaultContext());

        assertEquals("{\"rank\":7,\"title\":\"warrior\"}", recording.setObjectValues.get(1));
    }

    @Test
    public void findByIdDeserializesJsonGenericField() {
        RecordingConnection recording = new RecordingConnection();
        recording.resultRow.put("id", 1L);
        recording.resultRow.put("bag", "{\"potion\":3}");
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PlayerWithBag.class);

        PlayerWithBag player = executor.findById(metadata, 1L, ExecutorContext.defaultContext());

        assertNotNull(player);
        assertEquals(Integer.valueOf(3), player.bag.get("potion"));
    }

    @Test
    public void findByIdDeserializesJsonPrivateObjectField() {
        RecordingConnection recording = new RecordingConnection();
        recording.resultRow.put("id", 1L);
        recording.resultRow.put("profile", "{\"rank\":7,\"title\":\"warrior\"}");
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PlayerWithProfile.class);

        PlayerWithProfile player = executor.findById(metadata, 1L, ExecutorContext.defaultContext());

        assertNotNull(player);
        assertEquals(7, player.profile.rank);
        assertEquals("warrior", player.profile.title);
    }

    @Test
    public void insertUsesTypeConverterForCustomTypeWithExplicitColumnType() {
        // 场景 B：复杂类型 + 显式非 JSON type，绑定走 TypeConverter 而非 JSON 序列化。
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        TypeConverterRegistry registry = new TypeConverterRegistry();
        registry.register(new MoneyConverter());
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource, registry);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PlayerWithMoney.class);

        // 元数据语义：显式类型抑制了 JSON 推断
        RdbFieldMetadata balance = metadata.fieldByPropertyName("balance");
        assertFalse(balance.isJsonField());
        assertEquals("BIGINT", balance.sqlType());

        executor.insert(metadata, new PlayerWithMoney(1L, Money.ofCents(250L)),
                ExecutorContext.defaultContext());

        // 绑定值是 converter 产出的 Long（250），不是 JSON 字符串
        assertEquals(250L, recording.setObjectValues.get(1));
    }

    @Test
    public void insertConvertsInstantAndAppliesColumnDefault() {
        Instant now = Instant.parse("2026-05-24T08:00:00Z");
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PlayerWithTime.class);

        executor.insert(metadata, new PlayerWithTime(1L, now), ExecutorContext.defaultContext());

        assertEquals(Timestamp.from(now), recording.setObjectValues.get(1));
        assertEquals(5, recording.setObjectValues.get(2));
    }

    @Test
    public void insertAppliesPrimitiveColumnDefault() {
        RecordingConnection recording = new RecordingConnection();
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PlayerWithPrimitiveDefault.class);

        executor.insert(metadata, new PlayerWithPrimitiveDefault(1L), ExecutorContext.defaultContext());

        assertEquals(1L, recording.setObjectValues.get(0));
        assertEquals(5, recording.setObjectValues.get(1));
    }

    @Test
    public void findByIdConvertsTimestampToInstant() {
        Instant now = Instant.parse("2026-05-24T08:00:00Z");
        RecordingConnection recording = new RecordingConnection();
        recording.resultRow.put("id", 1L);
        recording.resultRow.put("updatedAt", Timestamp.from(now));
        recording.resultRow.put("level", 5);
        DataSource dataSource = new RecordingDataSource(recording);
        MysqlRdbExecutor executor = new MysqlRdbExecutor(dataSource);
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PlayerWithTime.class);

        PlayerWithTime player = executor.findById(metadata, 1L, ExecutorContext.defaultContext());

        assertEquals(now, player.updatedAt);
        assertEquals(5, player.level);
    }


    private record RecordingDataSource(RecordingConnection recording) implements DataSource {
        @Override
        public Connection getConnection() {
            return recording.proxy();
        }

        @Override
        public Connection getConnection(String username, String password) {
            return recording.proxy();
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class RecordingConnection {
        private boolean autoCommit = true;
        private boolean rollbackCalled;
        private boolean commitCalled;
        private boolean autoCommitRestored;
        private SQLException prepareStatementFailure;
        private int executeUpdateResult = 1;
        private final Map<String, Object> resultRow = new LinkedHashMap<>();
        private final List<Integer> setObjectIndices = new ArrayList<>();
        private final List<Object> setObjectValues = new ArrayList<>();

        private Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) args[0];
                            if (autoCommit) {
                                autoCommitRestored = true;
                            }
                            yield null;
                        }
                        case "prepareStatement" -> {
                            if (prepareStatementFailure != null) {
                                throw prepareStatementFailure;
                            }
                            yield preparedStatement();
                        }
                        case "rollback" -> {
                            rollbackCalled = true;
                            yield null;
                        }
                        case "commit" -> {
                            commitCalled = true;
                            yield null;
                        }
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] { PreparedStatement.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "executeQuery" -> resultSet();
                        case "executeBatch" -> throw new SQLException("boom");
                        case "executeUpdate" -> executeUpdateResult;
                        case "setObject" -> {
                            setObjectIndices.add((Integer) args[0]);
                            setObjectValues.add(args[1]);
                            yield null;
                        }
                        case "addBatch", "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet resultSet() {
            boolean[] consumed = { false };
            // resultRow is populated in entity field order, which is also the SELECT
            // projection order, so positional getObject(index) mirrors a real driver.
            List<Object> orderedValues = new ArrayList<>(resultRow.values());
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            if (resultRow.isEmpty() || consumed[0]) {
                                yield false;
                            }
                            consumed[0] = true;
                            yield true;
                        }
                        case "getObject" -> args[0] instanceof Integer index
                                ? orderedValues.get(index - 1)
                                : resultRow.get((String) args[0]);
                        case "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    @Entity
    @Table(name = "player")
    private static class Player {
        @Id
        @Column
        private long id;

        private Player(long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "versioned_player")
    private static class VersionedPlayer {
        @Id
        @Column
        private long id;
        @Column(name = "player_name")
        private String name;
        @Version
        @Column
        private long version;

        private VersionedPlayer(long id, String name, long version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }

    @Entity
    @Table(name = "player_with_bag")
    private static class PlayerWithBag {
        @Id
        @Column
        private long id;

        // 复杂类型默认按 JSON 列存储；仍需 @Column 才会映射
        @Column
        private Map<String, Integer> bag;
    }

    @Entity
    @Table(name = "player_with_profile")
    private static class PlayerWithProfile {
        @Id
        @Column
        private long id;

        // 复杂类型默认按 JSON 列存储；仍需 @Column 才会映射
        @Column
        private Profile profile;

        private PlayerWithProfile() {
        }

        private PlayerWithProfile(long id, Profile profile) {
            this.id = id;
            this.profile = profile;
        }
    }

    private static class Profile {
        private int rank;
        private String title;

        private Profile() {
        }

        private Profile(int rank, String title) {
            this.rank = rank;
            this.title = title;
        }
    }

    private static final class Money {
        private final long cents;

        private Money(long cents) {
            this.cents = cents;
        }

        private static Money ofCents(long cents) {
            return new Money(cents);
        }
    }

    private static final class MoneyConverter implements TypeConverter<Money, Long> {
        @Override public Class<Money> sourceType() { return Money.class; }
        @Override public Class<Long> targetType() { return Long.class; }
        @Override public Long write(Money source) { return source.cents; }
        @Override public Money read(Long target) { return Money.ofCents(target); }
    }

    @Entity
    @Table(name = "player_with_money")
    private static class PlayerWithMoney {
        @Id
        @Column
        private long id;

        @Column(type = ColumnType.BIGINT)
        private Money balance;

        private PlayerWithMoney() {
        }

        private PlayerWithMoney(long id, Money balance) {
            this.id = id;
            this.balance = balance;
        }
    }

    @Entity
    @Table(name = "player_with_time")
    private static class PlayerWithTime {
        @Id
        @Column
        private long id;
        @Column
        private Instant updatedAt;
        @Column(defaultValue = "5")
        private Integer level;

        private PlayerWithTime() {
        }

        private PlayerWithTime(long id, Instant updatedAt) {
            this.id = id;
            this.updatedAt = updatedAt;
        }
    }

    @Entity
    @Table(name = "player_with_primitive_default")
    private static class PlayerWithPrimitiveDefault {
        @Id
        @Column
        private long id;
        @Column(defaultValue = "5")
        private int level;

        private PlayerWithPrimitiveDefault() {
        }

        private PlayerWithPrimitiveDefault(long id) {
            this.id = id;
        }
    }
}
