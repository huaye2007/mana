package cn.managame.jpa.rdb.cache;

import cn.managame.jpa.cache.CacheCompositeKey;
import cn.managame.jpa.cache.CacheConfig;
import cn.managame.jpa.cache.NewRolePolicy;
import cn.managame.jpa.cache.annotation.CacheKey;
import cn.managame.jpa.core.annotation.RoleId;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.routing.ShardRoutingResolver;
import cn.managame.jpa.core.write.ShardWriteRouter;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.core.annotation.ShardKey;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import cn.managame.jpa.rdb.repository.DefaultRdbRepository;
import cn.managame.jpa.starter.GameJpaBootstrap;
import cn.managame.jpa.starter.GameJpaContext;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class RdbBatchExecutorTest {

    @Test
    public void routerResolvesShardFromEntityForDelete() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        ShardWriteRouter router = new ShardWriteRouter(metadata, new NumericRoutingStrategy(), "RDB");

        Player entity = new Player();
        entity.id = 100L;
        entity.roleId = 7L;

        WriteDestination dest = router.resolve(entity, entity.id, null);

        assertEquals("ds_7", dest.dataSource());
        assertEquals("player_7", dest.physicalTable());
    }

    @Test
    public void routerFailsWithoutEntityWhenShardKeyIsNotId() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        ShardWriteRouter router = new ShardWriteRouter(metadata, new NumericRoutingStrategy(), "RDB");

        assertThrows(GameJpaException.class, () -> router.resolve(null, 100L, null));
    }

    @Test
    public void saveUsesBatchUpsertWithRoutedContext() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        RdbBatchExecutor batchExecutor = new RdbBatchExecutor(metadata, executor, MetricsCollector.NOOP);

        Player entity = new Player();
        entity.id = 100L;
        entity.roleId = 8L;
        ExecutorContext ctx = ExecutorContext.of("ds_8", null, "player_8");

        batchExecutor.flush(WriteTask.Op.SAVE,
                List.of(new WriteTask("player", WriteTask.Op.SAVE, entity, entity.id)), ctx);

        assertEquals("batchUpsert", executor.lastOperation);
        assertEquals(1, executor.lastBatchSize);
        assertSame(ctx, executor.lastContext);
    }

    @Test
    public void deleteUsesBatchDeleteWithRoutedContext() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        RdbBatchExecutor batchExecutor = new RdbBatchExecutor(metadata, executor, MetricsCollector.NOOP);
        ExecutorContext ctx = ExecutorContext.of("ds_7", null, "player_7");

        batchExecutor.flush(WriteTask.Op.DELETE,
                List.of(new WriteTask("player", WriteTask.Op.DELETE, null, 100L)), ctx);

        assertEquals("batchDelete", executor.lastOperation);
        assertEquals(1, executor.lastBatchSize);
        assertSame(ctx, executor.lastContext);
    }

    @Test
    public void routerResolvesShardFromEntityForSave() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        ShardWriteRouter router = new ShardWriteRouter(metadata, new NumericRoutingStrategy(), "RDB");

        Player entity = new Player();
        entity.id = 100L;
        entity.roleId = 8L;

        WriteDestination dest = router.resolve(entity, entity.id, null);

        assertEquals("ds_8", dest.dataSource());
        assertEquals("player_8", dest.physicalTable());
    }

    @Test
    public void uniqueCacheDeleteWithRoutingKeyBuildsRoutableDeleteEntity() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<Player, Long> delegate = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
        CapturingSubmitter submitter = new CapturingSubmitter();
        RdbUniqueCacheRepository<Player, Long> repository = new RdbUniqueCacheRepository<>(
                metadata, delegate, CacheConfig.defaults(), submitter);
        ShardWriteRouter router = new ShardWriteRouter(metadata, new NumericRoutingStrategy(), "RDB");

        repository.cacheDelete(100L, 7L);

        // 提交期路由从 cacheDelete 编码进任务实体的 @ShardKey 解析出分片目标。
        WriteDestination dest = router.resolve(submitter.entity, submitter.id, null);
        assertEquals("ds_7", dest.dataSource());
        assertEquals("player_7", dest.physicalTable());
    }

    @Test
    public void uniqueCacheLoadWithRoutingKeyLoadsCorrectShard() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        Player loaded = new Player();
        loaded.id = 100L;
        loaded.roleId = 7L;
        executor.findResult = loaded;
        DefaultRdbRepository<Player, Long> delegate = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
        RdbUniqueCacheRepository<Player, Long> repository = new RdbUniqueCacheRepository<>(
                metadata, delegate, CacheConfig.defaults(), new CapturingSubmitter(), true);

        assertSame(loaded, repository.cacheLoad(100L, 7L));
        assertEquals("ds_7", executor.lastContext.dataSourceName());
        assertEquals("player_7", executor.lastContext.physicalTableName());
    }

    @Test
    public void uniqueCacheLoadWithRoutingKeySkipsExecutorForNewRole() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<Player, Long> delegate = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
        RdbUniqueCacheRepository<Player, Long> repository = new RdbUniqueCacheRepository<>(
                metadata, delegate,
                CacheConfig.defaults(), new CapturingSubmitter(), true,
                NewRolePolicy.of(roleId -> roleId.equals(7L), Duration.ofMinutes(5)));

        assertNull(repository.cacheLoad(100L, 7L));
        assertNull(executor.lastOperation);
    }

    @Test
    public void moduleNewRoleDetectorAppliesGloballyToRepositories() {
        RecordingExecutor executor = new RecordingExecutor();
        GameJpaContext context = new GameJpaBootstrap()
                .install(RdbCacheModule.withExecutor(executor)
                        .newRoleDetector(roleId -> roleId.equals(7L), Duration.ofMinutes(5)))
                .bootstrap(List.of(Player.class));

        try {
            PlayerCacheRepository repository = context.getRepository(PlayerCacheRepository.class);

            assertNull(repository.cacheLoad(100L, 7L));
            assertNull(executor.lastOperation);
        } finally {
            context.close();
        }
    }

    @Test
    public void uniqueCacheLoadFailsFastWhenShardKeyCannotBeInferred() {
        assertThrows(GameJpaException.class, () -> {
            RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
            RecordingExecutor executor = new RecordingExecutor();
            DefaultRdbRepository<Player, Long> delegate = new DefaultRdbRepository<>(
                    metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
            RdbUniqueCacheRepository<Player, Long> repository = new RdbUniqueCacheRepository<>(
                    metadata, delegate, CacheConfig.defaults(), new CapturingSubmitter(), true);
    
            repository.cacheLoad(100L);
        });
    }

    @Test
    public void uniqueCacheDeleteFailsFastWhenShardKeyCannotBeInferred() {
        assertThrows(GameJpaException.class, () -> {
            RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
            RecordingExecutor executor = new RecordingExecutor();
            DefaultRdbRepository<Player, Long> delegate = new DefaultRdbRepository<>(
                    metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
            RdbUniqueCacheRepository<Player, Long> repository = new RdbUniqueCacheRepository<>(
                    metadata, delegate, CacheConfig.defaults(), new CapturingSubmitter(), true);
    
            repository.cacheDelete(100L);
        });
    }

    @Test
    public void multiCacheLoadRoutesFromShardKeyCacheField() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Mail.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<Mail, Long> delegate = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
        RdbMultiCacheRepository<Mail, Long> repository = new RdbMultiCacheRepository<>(
                null, metadata, delegate, RdbCacheKeyMeta.resolve(metadata), CacheConfig.defaults(),
                (entityName, op, entity, id) -> {
                });

        repository.cacheLoad(CacheCompositeKey.of(9L));

        assertEquals("ds_9", executor.lastContext.dataSourceName());
        assertEquals("mail_9", executor.lastContext.physicalTableName());
    }

    @Test
    public void multiCacheLoadUsesAnnotatedRoleIdPartForNewRoleCheck() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(MailByType.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<MailByType, Long> delegate = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
        RdbMultiCacheRepository<MailByType, Long> repository = new RdbMultiCacheRepository<>(
                null, metadata, delegate, RdbCacheKeyMeta.resolve(metadata),
                CacheConfig.defaults(),
                (entityName, op, entity, id) -> {
                },
                NewRolePolicy.of(roleId -> roleId.equals(9L), Duration.ofMinutes(5)));

        assertTrue(repository.cacheLoad(CacheCompositeKey.of("system", 9L)).isEmpty());
        assertNull(executor.lastContext);
    }

    @Test
    public void multiCacheLoadWithoutRoleIdAnnotationUsesLoader() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(NonRoleMail.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<NonRoleMail, Long> delegate = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP, new NumericRoutingStrategy());
        RdbMultiCacheRepository<NonRoleMail, Long> repository = new RdbMultiCacheRepository<>(
                null, metadata, delegate, RdbCacheKeyMeta.resolve(metadata),
                CacheConfig.defaults(),
                (entityName, op, entity, id) -> {
                },
                NewRolePolicy.of(roleId -> true, Duration.ofMinutes(5)));

        assertTrue(repository.cacheLoad(CacheCompositeKey.of(9L)).isEmpty());
        assertEquals("ds_9", executor.lastContext.dataSourceName());
    }

    @Test
    public void cacheInsertUsesInternalWriteEntityNameAndAppliesColumnDefault() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(DefaultedPlayer.class);
        RecordingExecutor executor = new RecordingExecutor();
        DefaultRdbRepository<DefaultedPlayer, Long> delegate = new DefaultRdbRepository<>(
                metadata, executor, new LifecycleDispatcher(), MetricsCollector.NOOP);
        CapturingSubmitter submitter = new CapturingSubmitter();
        RdbUniqueCacheRepository<DefaultedPlayer, Long> repository = new RdbUniqueCacheRepository<>(
                metadata, delegate, CacheConfig.defaults(), submitter, "rdb:" + DefaultedPlayer.class.getName(),
                false);

        DefaultedPlayer player = new DefaultedPlayer();
        player.id = 1L;
        repository.cacheInsert(player);

        assertEquals("rdb:" + DefaultedPlayer.class.getName(), submitter.entityName);
        assertEquals(5, player.level);
        assertEquals(Integer.valueOf(3), player.bag.get("potion"));
    }

    @Entity
    @Table(name = "player")
    static class Player {
        @Id
        @Column
        private long id;
        @ShardKey
        @RoleId
        @Column
        private long roleId;
    }

    interface PlayerCacheRepository extends IRdbUniqueCacheRepository<Player, Long> {
    }

    @Entity
    @Table(name = "mail")
    static class Mail {
        @Id
        @Column
        private long id;
        @Column
        private long ownerId;
        @CacheKey(order = 0)
        @ShardKey
        @RoleId
        @Column
        private long roleId;
    }

    @Entity
    @Table(name = "mail_by_type")
    static class MailByType {
        @Id
        @Column
        private long id;
        @CacheKey(order = 0)
        @Column
        private String type;
        @CacheKey(order = 1)
        @ShardKey
        @RoleId
        @Column
        private long roleId;
    }

    @Entity
    @Table(name = "non_role_mail")
    static class NonRoleMail {
        @Id
        @Column
        private long id;
        @CacheKey(order = 0)
        @ShardKey
        @Column
        private long ownerId;
    }

    @Entity
    @Table(name = "defaulted_player")
    static class DefaultedPlayer {
        @Id
        @Column
        private long id;
        @Column(defaultValue = "5")
        private Integer level;
        @Column(defaultValue = "{\"potion\":3}")
        private Map<String, Integer> bag;
    }

    @Test
    public void flushMetricIsTaggedByPhysicalTable() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        TagRecordingMetrics metrics = new TagRecordingMetrics();
        RdbBatchExecutor batchExecutor = new RdbBatchExecutor(metadata, new RecordingExecutor(), metrics);
        ExecutorContext ctx = ExecutorContext.of("ds_8", null, "player_8");

        Player entity = new Player();
        entity.id = 100L;
        entity.roleId = 8L;
        batchExecutor.flush(WriteTask.Op.SAVE,
                List.of(new WriteTask("player", WriteTask.Op.SAVE, entity, entity.id)), ctx);

        assertEquals("player_8", metrics.lastEntity, "埋点应按物理表名打标签");
    }

    @Test
    public void nonShardedEntityRoutesToHomeDataSource() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(LoginLog.class);

        // 写路径：非分片实体落到注解声明的 home 库，物理表用逻辑名（null 回退）。
        ShardWriteRouter router = new ShardWriteRouter(metadata, null, "RDB");
        WriteDestination dest = router.resolve(new LoginLog(), 1L, null);
        assertEquals("log", dest.dataSource());
        assertNull(dest.physicalTable());

        // 读路径：home 库同样生效。
        ShardRoutingResolver resolver = new ShardRoutingResolver(metadata, null, "RDB");
        assertEquals("log", resolver.resolveFromId(1L).dataSourceName());
    }

    @Entity
    @Table(name = "login_log", dataSource = "log")
    static class LoginLog {
        @Id
        @Column
        private long id;
    }

    private static class NumericRoutingStrategy implements RoutingStrategy {
        @Override
        public String resolveDataSource(String logicalName, Object routingKey) {
            return "ds_" + routingKey;
        }

        @Override
        public String resolvePhysicalName(String logicalName, Object routingKey) {
            return logicalName + "_" + routingKey;
        }
    }

    private static class TagRecordingMetrics implements MetricsCollector {
        private volatile String lastEntity;

        @Override
        public void recordLatency(String operation, String entity, long millis) {
            this.lastEntity = entity;
        }

        @Override
        public void recordCount(String operation, String entity, int count) {
            this.lastEntity = entity;
        }

        @Override
        public void recordError(String operation, String entity, Throwable error) {
        }
    }

    private static class RecordingExecutor implements RdbExecutor {
        private ExecutorContext lastContext;
        private String lastOperation;
        private int lastBatchSize;
        private Object findResult;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T findById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
            this.lastContext = context;
            this.lastOperation = "findById";
            return (T) findResult;
        }

        @Override
        public <T> List<T> findAll(RdbEntityMetadata metadata, ExecutorContext context) {
            return List.of();
        }

        @Override
        public void insert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        }

        @Override
        public void update(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        }

        @Override
        public void upsert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        }

        @Override
        public void deleteById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
        }

        @Override
        public void batchDelete(RdbEntityMetadata metadata, List<?> ids, ExecutorContext context) {
            this.lastContext = context;
            this.lastOperation = "batchDelete";
            this.lastBatchSize = ids.size();
        }

        @Override
        public void batchInsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        }

        @Override
        public void batchUpdate(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        }

        @Override
        public void batchUpsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
            this.lastContext = context;
            this.lastOperation = "batchUpsert";
            this.lastBatchSize = entities.size();
        }

        @Override
        public <T> List<T> query(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
            this.lastContext = context;
            return List.of();
        }

        @Override
        public long count(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
            return 0;
        }
    }

    private static class CapturingSubmitter implements WriteTaskSubmitter {
        private String entityName;
        private Object entity;
        private Object id;

        @Override
        public void submit(String entityName, Op op, Object entity, Object id) {
            this.entityName = entityName;
            this.entity = entity;
            this.id = id;
        }
    }
}
