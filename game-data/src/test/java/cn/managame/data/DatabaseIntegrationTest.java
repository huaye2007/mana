package cn.managame.data;

import cn.managame.data.annotation.Id;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.mysql.*;
import cn.managame.data.mongo.*;
import com.mongodb.client.MongoClients;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in server tests. Supply a dedicated disposable MySQL database, never production. */
class DatabaseIntegrationTest {
    @Table(name="ogbs_data_probe",indexes=@Index(columnList="score"))
    static class MysqlPlayer { @Id @Column long id; @Column int score; }
    @cn.managame.data.mongo.Collection(name="ogbs_data_probe",indexes=@MongoIndex(fields={"score"}))
    static class MongoPlayer { @Id @cn.managame.data.mongo.Field long id; @cn.managame.data.mongo.Field int score; }

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
            var mapper=new MysqlEntityMapper(access); mapper.initialize(meta);
            new MysqlEntityMapper(access).initialize(meta); // Existing schema/index compatibility.
            var player=new MysqlPlayer(); player.id=1; player.score=42; mapper.insertBatch(meta,List.of(player));
            assertEquals(42,((MysqlPlayer)mapper.load(meta,1L)).score);
            player.score=99;
            assertThrows(MysqlException.class,()->mapper.deleteInsertBatch(meta,List.of(player,player)));
            assertEquals(42,((MysqlPlayer)mapper.load(meta,1L)).score);
            mapper.updateBatch(meta,List.of(player)); assertEquals(99,((MysqlPlayer)mapper.load(meta,1L)).score);
            mapper.deleteBatch(meta,List.of(1L)); assertNull(mapper.load(meta,1L));
        } finally { access.update("DROP TABLE IF EXISTS ogbs_data_probe",new Object[0]); }
    }
    @Test @EnabledIfEnvironmentVariable(named="OGBS_DATA_MONGO_URI",matches=".+")
    void mongoServerReplacementAndIndexes() {
        try(var client=MongoClients.create(System.getenv("OGBS_DATA_MONGO_URI"))) {
            var database=client.getDatabase("ogbs_data_test_"+UUID.randomUUID().toString().replace("-",""));
            try {
                var mapper=new MongoEntityMapper(database);
                var meta=new EntityMeta(MongoPlayer.class,EntityMeta.Kind.SINGLE); mapper.initialize(meta);
                new MongoEntityMapper(database).initialize(meta);
                var player=new MongoPlayer(); player.id=1; player.score=42;
                mapper.updateBatch(meta,List.of(player)); assertNull(mapper.load(meta,1L));
                mapper.deleteInsertBatch(meta,List.of(player));
                assertEquals(42,((MongoPlayer)mapper.load(meta,1L)).score);
                mapper.deleteBatch(meta,List.of(1L)); assertNull(mapper.load(meta,1L));
            } finally { database.drop(); }
        }
    }
}
