package cn.managame.data;

import cn.managame.data.annotation.Id;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.mysql.*;
import cn.managame.data.mongo.*;
import com.mongodb.client.MongoClients;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import cn.managame.data.error.DataOperationException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in server tests. Supply a dedicated disposable MySQL database, never production. */
class DatabaseIntegrationTest {
    @Table(name="ogbs_data_probe",indexes=@Index(columnList="score"))
    static class MysqlPlayer { @Id @Column long id; @Column int score; }
    @cn.managame.data.mongo.Collection(name="ogbs_data_probe",indexes=@MongoIndex(fields={"score"}))
    static class MongoPlayer { @Id @cn.managame.data.mongo.Field long id; @cn.managame.data.mongo.Field int score; }
    @Table(name="ogbs_data_flood")
    public static class FloodPlayer { @Id @Column public long id; @Column public int score; }
    public static final class FloodPlayers extends SingleRepository<Long,FloodPlayer> {}

    @Test @EnabledIfEnvironmentVariable(named="OGBS_DATA_MYSQL_URL",matches=".+")
    void mysqlServerSchemaRoundTripAndRollback() {
        MysqlDataSource source=new MysqlDataSource();
        source.setURL(System.getenv("OGBS_DATA_MYSQL_URL"));
        source.setUser(System.getenv().getOrDefault("OGBS_DATA_MYSQL_USER","root"));
        source.setPassword(System.getenv().getOrDefault("OGBS_DATA_MYSQL_PASSWORD",""));
        var access=new JdbcMysqlAccess(source);
        long present=access.<Long>queryOne("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ogbs_data_probe'",
                new Object[0],r->r.getLong(1));
        assertEquals(0,present,"Use a dedicated database without ogbs_data_probe");
        try {
            var meta=new EntityMeta(MysqlPlayer.class,EntityMeta.Kind.SINGLE);
            var mapper=new MysqlEntityMapper(access); mapper.initialize(meta,SchemaMode.CREATE);
            new MysqlEntityMapper(access).initialize(meta,SchemaMode.CREATE); // Existing schema/index compatibility.
            var player=new MysqlPlayer(); player.id=1; player.score=42; mapper.insertBatch(meta,List.of(player));
            assertEquals(42,((MysqlPlayer)mapper.load(meta,1L)).score);
            player.score=99;
            assertThrows(MysqlException.class,()->mapper.deleteInsertBatch(meta,List.of(player,player)));
            assertEquals(42,((MysqlPlayer)mapper.load(meta,1L)).score);
            mapper.updateBatch(meta,List.of(player)); assertEquals(99,((MysqlPlayer)mapper.load(meta,1L)).score);
            mapper.deleteBatch(meta,List.of(1L)); assertNull(mapper.load(meta,1L));
        } finally { access.update("DROP TABLE IF EXISTS ogbs_data_probe",new Object[0]); }
    }
    /**
     * The flood that used to end in heap exhaustion: producers with no rate limit, a real database that is
     * healthy throughout and simply slower than they are. What this pins down is that the dirty set stays at
     * its bound, that passes keep completing while the flood runs (the failure had zero), that a refusal
     * writes nothing, and that close still puts every accepted row in the database.
     */
    @Test @EnabledIfEnvironmentVariable(named="OGBS_DATA_MYSQL_URL",matches=".+")
    void unthrottledProducersAreBoundedAndEveryAcceptedChangeIsStored() throws Exception {
        int bound = 5_000, producers = 4;
        var source = mysqlSource();
        var access = new JdbcMysqlAccess(source);
        assertEquals(0, tableCount(access), "Use a dedicated database without ogbs_data_flood");
        var accepted = new AtomicLong(); var refused = new AtomicLong(); var next = new AtomicLong();
        var running = new AtomicBoolean(true); var worstPending = new AtomicLong(); var worstCache = new AtomicLong();
        var data = GameDataBuilder.builder().mysql(source).repositories(FloodPlayers.class)
                .flushInterval(Duration.ofMillis(100)).cacheExpire(Duration.ofMinutes(1)).batchSize(500)
                .maxPendingChanges(bound).pendingChangeWait(Duration.ofMillis(200))
                .maxCacheEntries(5_000).build();
        try {
            var players = data.repository(FloodPlayers.class);
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < producers; i++) threads.add(Thread.ofPlatform().start(() -> {
                while (running.get()) {
                    var player = new FloodPlayer(); player.id = next.incrementAndGet(); player.score = 1;
                    try { players.insert(player); accepted.incrementAndGet(); }
                    catch (DataOperationException full) { refused.incrementAndGet(); }
                }
            }));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long passesAtStart = data.stats().flushes();
            while (System.nanoTime() < until) {
                worstPending.accumulateAndGet(data.stats().pendingChanges(), Math::max);
                worstCache.accumulateAndGet(data.stats().singleCacheEntries(), Math::max);
                Thread.sleep(5);
            }
            long passes = data.stats().flushes() - passesAtStart;
            running.set(false);
            for (Thread thread : threads) thread.join();

            assertTrue(refused.get() > 0, "producers outran storage, so some writes must have been refused");
            assertTrue(accepted.get() > 0);
            assertEquals(refused.get(), data.stats().rejectedChanges());
            assertTrue(worstPending.get() <= bound + producers,
                    "dirty set reached " + worstPending.get() + " with a bound of " + bound);
            assertTrue(passes >= 3, "only " + passes + " passes completed while the flood ran");
            // Its bound, plus the dirty entries that cannot be evicted, plus what arrived since the last
            // sweep: a budget approached between passes, not a limit enforced as each entry is added.
            assertTrue(worstCache.get() <= 2L * (5_000 + bound), "cache reached " + worstCache.get() + " entries");
            assertTrue(accepted.get() > 2 * worstCache.get(), "the flood made fewer entities than the cache held");
            data.close();
            assertEquals(accepted.get(), rowCount(access), "close must store every accepted change, and only those");
        } finally {
            try { data.close(); } catch (RuntimeException alreadyClosed) { /* the assertions above report first */ }
            access.update("DROP TABLE IF EXISTS ogbs_data_flood", new Object[0]);
        }
    }
    private static MysqlDataSource mysqlSource() {
        MysqlDataSource source = new MysqlDataSource();
        source.setURL(System.getenv("OGBS_DATA_MYSQL_URL"));
        source.setUser(System.getenv().getOrDefault("OGBS_DATA_MYSQL_USER","root"));
        source.setPassword(System.getenv().getOrDefault("OGBS_DATA_MYSQL_PASSWORD",""));
        return source;
    }
    private static long tableCount(JdbcMysqlAccess access) {
        return access.<Long>queryOne("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ogbs_data_flood'",
                new Object[0], r -> r.getLong(1));
    }
    private static long rowCount(JdbcMysqlAccess access) {
        return access.<Long>queryOne("SELECT COUNT(*) FROM ogbs_data_flood", new Object[0], r -> r.getLong(1));
    }

    @Test @EnabledIfEnvironmentVariable(named="OGBS_DATA_MONGO_URI",matches=".+")
    void mongoServerReplacementAndIndexes() {
        try(var client=MongoClients.create(System.getenv("OGBS_DATA_MONGO_URI"))) {
            var database=client.getDatabase("ogbs_data_test_"+UUID.randomUUID().toString().replace("-",""));
            try {
                var mapper=new MongoEntityMapper(database);
                var meta=new EntityMeta(MongoPlayer.class,EntityMeta.Kind.SINGLE); mapper.initialize(meta,SchemaMode.CREATE);
                new MongoEntityMapper(database).initialize(meta,SchemaMode.CREATE);
                var player=new MongoPlayer(); player.id=1; player.score=42;
                mapper.updateBatch(meta,List.of(player)); assertNull(mapper.load(meta,1L));
                mapper.deleteInsertBatch(meta,List.of(player));
                assertEquals(42,((MongoPlayer)mapper.load(meta,1L)).score);
                mapper.deleteBatch(meta,List.of(1L)); assertNull(mapper.load(meta,1L));
            } finally { database.drop(); }
        }
    }
}
