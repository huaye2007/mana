package cn.managame.jpa.demo;

import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.lifecycle.LifecycleListener;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.demo.domain.PlayerAccount;
import cn.managame.jpa.demo.domain.PlayerProfile;
import cn.managame.jpa.demo.executor.InMemoryDocExecutor;
import cn.managame.jpa.demo.executor.InMemoryRdbExecutor;
import cn.managame.jpa.demo.repository.PlayerAccountRepository;
import cn.managame.jpa.demo.repository.PlayerProfileRepository;
import cn.managame.jpa.docdb.DocdbModule;
import cn.managame.jpa.docdb.metadata.DocEntityMetadata;
import cn.managame.jpa.docdb.query.DocQuerySpec;
import cn.managame.jpa.docdb.query.DocUpdateSpec;
import cn.managame.jpa.core.metrics.DefaultMetricsCollector;
import cn.managame.jpa.rdb.RdbModule;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import cn.managame.jpa.starter.GameJpaBootstrap;
import cn.managame.jpa.starter.GameJpaContext;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class GameJpaComplexDemoTest {

    @Test
    public void complexPlayerScenarioExercisesMetadataRoutingRepositoriesAndMetrics() {
        InMemoryRdbExecutor rdb = new InMemoryRdbExecutor();
        InMemoryDocExecutor doc = new InMemoryDocExecutor();
        DefaultMetricsCollector metrics = new DefaultMetricsCollector();
        RecordingLifecycle lifecycle = new RecordingLifecycle();

        GameJpaContext context = new GameJpaBootstrap()
                .use(RdbModule.withExecutor(rdb))
                .use(DocdbModule.withExecutor(doc))
                .routingStrategy(new ModuloRoutingStrategy())
                .metricsCollector(metrics)
                .addLifecycleListener(lifecycle)
                .bootstrap(List.of(PlayerAccount.class, PlayerProfile.class));

        try {
            assertMetadata(context);

            PlayerAccountRepository accounts = context.getRepository(PlayerAccountRepository.class);
            PlayerProfileRepository profiles = context.getRepository(PlayerProfileRepository.class);

            PlayerAccount first = new PlayerAccount(1001L, 101, "knight-1001", 30, 9_000);
            first.getBag().put("sword", 1);
            first.getBag().put("gem", 24);
            accounts.insert(first);
            accounts.batchInsert(List.of(
                    new PlayerAccount(1002L, 102, "mage-1002", 18, 5_500),
                    new PlayerAccount(1003L, 101, "archer-1003", 25, 7_200),
                    new PlayerAccount(1004L, 103, "priest-1004", 9, 1_300)));

            assertMissingRoutingKey(() -> accounts.findById(1001L));
            assertSame(first, accounts.findById(1001L, 101));
            List<PlayerAccount> highLevelOnServer101 = accounts.findBySpec(
                    new RdbQuerySpec().gte("level", 20).orderByDesc("gold").limit(2), 101);
            assertEquals(List.of(1001L, 1003L),
                    highLevelOnServer101.stream().map(PlayerAccount::getPlayerId).toList());

            PlayerProfile profile = new PlayerProfile(1001L, 101, "Knight");
            profile.getStats().setArenaRank(12);
            profile.getStats().setPower(58_000);
            profile.getAchievements().add("first-blood");
            profiles.insert(profile);
            assertMissingRoutingKey(() -> profiles.findById(1001L));
            profiles.update(1001L, new DocUpdateSpec().set("nickname", "Knight Prime"), 101);
            List<PlayerProfile> renamedProfiles = profiles.find(
                    new DocQuerySpec().eq("nickname", "Knight Prime").exists("achievements", true), 101);
            assertEquals(1, renamedProfiles.size());
            assertEquals("Knight Prime", profiles.findById(1001L, 101).getNickname());

            assertEquals(4, rdb.rowCount());
            assertTrue(containsPhysicalName(rdb.contexts(), "player_account_01"));
            assertTrue(containsPhysicalName(rdb.contexts(), "player_account_02"));
            assertTrue(containsPhysicalName(doc.contexts(), "player_profile_01"));
            assertTrue(metrics.getCount("insert", "player_account") >= 1);
            assertTrue(metrics.getCount("findById", "player_account") >= 1);
            assertTrue(lifecycle.events.contains("beforeInsert:PlayerAccount"));
        } finally {
            context.close();
        }
    }

    private void assertMetadata(GameJpaContext context) {
        RdbEntityMetadata account = context.metadataRegistry()
                .get(PlayerAccount.class, RdbEntityMetadata.class)
                .orElseThrow();
        assertEquals("player_account", account.tableName());
        assertTrue(account.hasShardKey());
        assertTrue(account.hasVersion());
        assertTrue(account.fieldByPropertyName("bag").isJsonField());
        assertEquals("server_id", account.shardKeyField().columnName());
        assertEquals(2, account.indexes().size());

        DocEntityMetadata profile = context.metadataRegistry()
                .get(PlayerProfile.class, DocEntityMetadata.class)
                .orElseThrow();
        assertEquals("player_profile", profile.collectionName());
        assertEquals("server_id", profile.shardKeyField().documentFieldName());
        assertEquals(1, profile.indexedFields().size());
    }

    private void assertMissingRoutingKey(Runnable action) {
        try {
            action.run();
            fail("Expected missing routing key failure");
        } catch (GameJpaException expected) {
            assertTrue(expected.getMessage().contains("requires explicit routingKey"));
        }
    }

    private boolean containsPhysicalName(List<ExecutorContext> contexts, String physicalName) {
        return contexts.stream().anyMatch(ctx -> physicalName.equals(ctx.physicalTableName()));
    }

    private static class ModuloRoutingStrategy implements RoutingStrategy {
        @Override
        public String resolveDataSource(String logicalName, Object routingKey) {
            int bucket = Math.floorMod(((Number) routingKey).intValue(), 2);
            return "game_ds_" + bucket;
        }

        @Override
        public String resolvePhysicalName(String logicalName, Object routingKey) {
            int bucket = Math.floorMod(((Number) routingKey).intValue(), 4);
            return logicalName + "_" + String.format("%02d", bucket);
        }
    }

    private static class RecordingLifecycle implements LifecycleListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void beforeInsert(Object entity) {
            events.add("beforeInsert:" + entity.getClass().getSimpleName());
        }

        @Override
        public void afterLoad(Object entity) {
            events.add("afterLoad:" + entity.getClass().getSimpleName());
        }
    }
}
