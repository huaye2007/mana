package cn.managame.core.mapping;

import cn.managame.annotation.*;
import cn.managame.core.*;
import cn.managame.core.access.DataAccess;
import cn.managame.core.repository.*;
import cn.managame.core.log.LogRepository;
import cn.managame.codec.jackson.JacksonJsonCodec;
import cn.managame.rdb.*;
import cn.managame.rdb.dialect.MySqlDialect;
import cn.managame.docdb.mongodb.*;
import com.mongodb.client.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MappingValidationTest {
    public static class Row { @Id public long id; @GroupKey public long owner; public List<String> rewards; }
    @Resident public static class ResidentRow extends Row { }
    public interface Rows extends SingleRepository<Row, Long> { }
    public interface Groups extends GroupRepository<Row, Long> { }
    public interface Logs extends LogRepository<Row> { }
    public interface ResidentRows extends SingleRepository<ResidentRow, Long> { }
    public static class Unsupported { @Id public long id; public OffsetTime time; }
    public interface UnsupportedRows extends SingleRepository<Unsupported, Long> { }
    public static class TimeRow { @Id public long id; public LocalTime time; }
    public interface Times extends SingleRepository<TimeRow, Long> { }

    static DataAccess access(boolean mongo, boolean json) {
        if (!mongo) return new RdbDataAccess(proxy(DataSource.class, (p,m,a)->{throw new AssertionError("Unexpected I/O: " + m);}),
                new MySqlDialect(), new EntityMapper(new RdbValueConverter(), json ? new JacksonJsonCodec() : null));
        MongoDatabase database = proxy(MongoDatabase.class, (p,m,a)->{throw new AssertionError("Unexpected I/O: " + m);});
        MongoClient client = proxy(MongoClient.class, (p,m,a)-> {
            if (m.getName().equals("getDatabase")) return database;
            throw new AssertionError(m);
        });
        return new MongoDataAccess(client, "test", new EntityMapper(new MongoValueConverter(), json ? new JacksonJsonCodec() : null), false);
    }
    @Test void missingCodecFailsBeforeSchemaPreloadAndWritesForEveryRepositoryMode() {
        for (boolean mongo : List.of(false, true)) for (boolean schema : List.of(false, true)) {
            for (Class<?> type : List.of(Rows.class, Groups.class, Logs.class, ResidentRows.class)) {
                try (var data = GameData.builder().dataAccess("test", access(mongo,false)).ensureSchema(schema).build()) {
                    var error = assertThrows(DataException.class, () -> data.repository(type));
                    assertTrue(error.getMessage().contains("rewards"));
                    assertTrue(error.getMessage().contains("JsonCodec"));
                }
            }
        }
    }
    @Test void correctlyConfiguredRepositoriesInitializeWithoutDatabaseAccess() {
        for (boolean mongo : List.of(false, true)) for (Class<?> type : List.of(Rows.class, Groups.class, Logs.class)) {
            try (var data = GameData.builder().dataAccess("test", access(mongo,true)).ensureSchema(false).build()) {
                assertNotNull(data.repository(type));
            }
        }
    }
    @Test void unsupportedTypesFailBeforeDatabaseAccessAndLocalTimeIsRdbOnly() {
        for (boolean mongo : List.of(false, true)) {
            try (var data = GameData.builder().dataAccess("test", access(mongo,true)).ensureSchema(false).build()) {
                assertThrows(DataException.class, () -> data.repository(UnsupportedRows.class));
                if (mongo) assertThrows(DataException.class, () -> data.repository(Times.class));
                else assertNotNull(data.repository(Times.class));
            }
        }
    }
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
