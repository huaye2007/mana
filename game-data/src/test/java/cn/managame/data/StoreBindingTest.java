package cn.managame.data;

import cn.managame.data.annotation.Id;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.mongo.Collection;
import cn.managame.data.mongo.Field;
import cn.managame.data.mongo.MongoAccess;
import cn.managame.data.mongo.MongoEntityMapper;
import cn.managame.data.mysql.JdbcMysqlAccess;
import cn.managame.data.mysql.MysqlException;
import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonValue;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * State and logs are separate stores. Nothing here goes through a container: these are the contracts the
 * builder owns, so that the container integration has one rule to follow rather than its own.
 */
class StoreBindingTest {
    @Collection(name="binding_players")
    static class Player { @Id @Field long id; @Field int score; }
    static class Players extends SingleRepository<Long,Player> {}

    /** Enough of a Mongo collection to take a state entity through the builder and back. */
    static class Memory implements MongoAccess {
        final Map<BsonValue,BsonDocument> documents=new LinkedHashMap<>();
        List<IndexDefinition> indexes;
        public CodecRegistry codecRegistry() { return MongoClientSettings.getDefaultCodecRegistry(); }
        public void initialize(String collection,List<IndexDefinition> declared,SchemaMode schema) { indexes=declared; }
        public BsonDocument findOne(String collection,BsonDocument filter) { return documents.get(filter.get("_id")); }
        public List<BsonDocument> find(String collection,BsonDocument filter) { return List.copyOf(documents.values()); }
        public void insertMany(String collection,List<BsonDocument> added) {
            for (BsonDocument document : added) documents.put(document.get("_id"),document);
        }
        public long replaceMany(String collection,List<BsonDocument> replacements,boolean upsert) {
            long matched=0;
            for (BsonDocument document : replacements) {
                boolean present=documents.containsKey(document.get("_id"));
                if (present) matched++;
                if (present||upsert) documents.put(document.get("_id"),document);
            }
            return matched;
        }
        public void deleteMany(String collection,BsonDocument filter) {
            for (BsonValue id : filter.getDocument("_id").getArray("$in")) documents.remove(id);
        }
    }

    static class Recording implements EntityMapper {
        final List<String> calls=new ArrayList<>();
        public void initialize(EntityMeta meta,SchemaMode schema) { calls.add("initialize"); }
        public Object load(EntityMeta meta,Object id) { calls.add("load"); return null; }
        public List<?> loadGroup(EntityMeta meta,GroupKeys groupKey) { calls.add("loadGroup"); return List.of(); }
        public void insertBatch(EntityMeta meta,List<?> entities) { calls.add("insertBatch"); }
        public void updateBatch(EntityMeta meta,List<?> entities) { calls.add("updateBatch"); }
        public void deleteBatch(EntityMeta meta,List<Object> ids) { calls.add("deleteBatch"); }
        public void deleteInsertBatch(EntityMeta meta,List<?> entities) { calls.add("deleteInsertBatch"); }
    }

    @Test void mongoStateAndMysqlLogsAreIndependentStores() {
        var state=new Memory(); var logs=new MysqlBuilderTest.Source();
        logs.execute("CREATE TABLE builder_logs (roles CLOB)");
        try(var data=GameDataBuilder.builder()
                .repositories(new MongoEntityMapper(state),Players.class)
                .logMysql(logs).logRepositories(MysqlBuilderTest.Logs.class).build()) {
            var player=new Player(); player.id=1; player.score=42;
            data.repository(Players.class).insert(player);
            var log=new MysqlBuilderTest.Log(); log.roles=Map.of(8,9L);
            assertTrue(data.repository(MysqlBuilderTest.Logs.class).insert(log));
        }
        assertEquals(42,state.documents.get(new BsonInt64(1)).getInt32("score").getValue());
        assertEquals("{\"8\":9}",new JdbcMysqlAccess(logs)
                .queryOne("SELECT roles FROM builder_logs",new Object[0],row->row.getString(1)));
    }

    @Test void logsNeverFallBackToTheStateSource() {
        var state=new MysqlBuilderTest.Source();
        // mysql(DataSource) binds state only, so a log Repository left unbound is refused rather than
        // silently written into the game database.
        var missing=assertThrows(IllegalArgumentException.class,()->GameDataBuilder.builder().mysql(state)
                .repositories(MysqlBuilderTest.Users.class).logRepositories(MysqlBuilderTest.Logs.class).build());
        assertTrue(missing.getMessage().contains("logMysql(DataSource)"),missing.getMessage());
        assertEquals(0,state.opened);
    }

    @Test void separateSourcesKeepLogRowsOutOfTheStateDatabase() {
        var state=new MysqlBuilderTest.Source(); var logs=new MysqlBuilderTest.Source();
        logs.execute("CREATE TABLE builder_logs (roles CLOB)");
        try(var data=GameDataBuilder.builder().mysql(state).logMysql(logs)
                .repositories(MysqlBuilderTest.Users.class).logRepositories(MysqlBuilderTest.Logs.class).build()) {
            var user=new MysqlBuilderTest.User(); user.id=1; user.roles.put(2,3L);
            data.repository(MysqlBuilderTest.Users.class).insert(user);
            var log=new MysqlBuilderTest.Log(); log.roles=Map.of(8,9L);
            assertTrue(data.repository(MysqlBuilderTest.Logs.class).insert(log));
        }
        assertEquals("{\"2\":3}",state.json(1));
        assertEquals("{\"8\":9}",new JdbcMysqlAccess(logs)
                .queryOne("SELECT roles FROM builder_logs",new Object[0],row->row.getString(1)));
        assertThrows(MysqlException.class,()->new JdbcMysqlAccess(state)
                .queryOne("SELECT roles FROM builder_logs",new Object[0],row->row.getString(1)));
    }

    @Test void namingABackendReplacesAnEarlierImplicitRegistration() {
        var recording=new Recording(); var state=new MysqlBuilderTest.Source();
        try(var data=GameDataBuilder.builder().mysql(state).repositories(MysqlBuilderTest.Users.class)
                // What a container integration discovered and registered, the application still redirects.
                .repositories(recording,MysqlBuilderTest.Users.class).build()) {
            assertNull(data.repository(MysqlBuilderTest.Users.class).get(1L));
        }
        assertEquals(List.of("initialize","load"),recording.calls);
        assertEquals(0,state.opened,"the MySQL default was replaced, so it was never opened");
    }

    @Test void repeatsThatAreNotRedirectionsStayDuplicates() {
        var recording=new Recording(); var state=new MysqlBuilderTest.Source();
        assertThrows(IllegalArgumentException.class,()->GameDataBuilder.builder()
                .repositories(recording,MysqlBuilderTest.Users.class).repositories(recording,MysqlBuilderTest.Users.class));
        assertThrows(IllegalArgumentException.class,()->GameDataBuilder.builder().mysql(state)
                .repositories(MysqlBuilderTest.Users.class).repositories(MysqlBuilderTest.Users.class));
        assertThrows(IllegalArgumentException.class,()->GameDataBuilder.builder()
                .repositories(recording,MysqlBuilderTest.Users.class).repositories(MysqlBuilderTest.Users.class));
    }
}
