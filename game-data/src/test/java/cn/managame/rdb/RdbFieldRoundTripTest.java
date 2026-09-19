package cn.managame.rdb;

import cn.managame.annotation.*;
import cn.managame.core.access.Query;
import cn.managame.core.mapping.EntityMapper;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.write.WriteOperation;
import cn.managame.codec.jackson.JacksonJsonCodec;
import cn.managame.rdb.dialect.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.LocalTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RdbFieldRoundTripTest {
    public static class Row {
        @Id public UUID id;
        @GroupKey public UUID owner;
        public List<String> rewards;
        public LocalTime time;
        public char code;
    }
    record Binding(String sql, Object value, Integer jdbcType) { }
    static class Jdbc {
        final Row row=new Row();
        final List<Binding> bindings=new ArrayList<>();
        int typedTimeReads;
        boolean jsonTime;
        Jdbc() { row.id=UUID.randomUUID();row.owner=UUID.randomUUID();row.rewards=List.of("gold");row.time=LocalTime.of(12,34,56,123456000);row.code='X'; }
        DataSource source() {
            return proxy(DataSource.class,(p,m,a)-> {
                if (!m.getName().equals("getConnection")) throw new AssertionError(m);
                return proxy(Connection.class,(c,method,args)->switch(method.getName()) {
                    case "prepareStatement" -> statement((String)args[0]);
                    case "getAutoCommit" -> true;
                    case "setAutoCommit", "setReadOnly", "commit", "rollback", "close" -> null;
                    default -> throw new AssertionError(method);
                });
            });
        }
        PreparedStatement statement(String sql) {
            int[] count={0};
            return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()) {
                case "setObject" -> {bindings.add(new Binding(sql,a[1],a.length==3 ? (Integer)a[2] : null));yield null;}
                case "addBatch" -> {count[0]++;yield null;}
                case "executeBatch" -> {int[] counts=new int[count[0]];Arrays.fill(counts,1);yield counts;}
                case "executeQuery" -> result();
                case "close", "setFetchSize" -> null;
                default -> throw new AssertionError(m);
            });
        }
        ResultSet result() {
            boolean[] first={true};
            return proxy(ResultSet.class,(p,m,a)->switch(m.getName()) {
                case "next" -> {boolean result=first[0];first[0]=false;yield result;}
                case "getObject" -> switch((String)a[0]) {
                    case "id" -> row.id;
                    case "owner" -> row.owner;
                    case "rewards" -> "[\"gold\"]";
                    case "code" -> "X";
                    case "time" -> {if (jsonTime) {assertEquals(1,a.length);yield "\"" + row.time + "\"";} assertEquals(2,a.length);assertEquals(LocalTime.class,a[1]);typedTimeReads++;yield row.time;}
                    default -> throw new AssertionError(a[0]);
                };
                case "close" -> null;
                default -> throw new AssertionError(m);
            });
        }
    }
    @Test void jdbcReadsTimeWithItsDeclaredTypeOnIdQueryAndScanPaths() {
        for (RdbDialect dialect : List.of(new MySqlDialect(),new PostgreSqlDialect())) {
            var jdbc=new Jdbc();var access=new RdbDataAccess(jdbc.source(),dialect,new EntityMapper(new RdbValueConverter(),new JacksonJsonCodec()));
            var metadata=EntityMetadata.inspect(Row.class);access.validateMapping(metadata);
            var byId=access.findById(metadata,jdbc.row.id).orElseThrow();
            assertEquals(jdbc.row.time,byId.time);assertEquals(jdbc.row.code,byId.code);assertEquals(jdbc.row.id,byId.id);assertEquals(jdbc.row.rewards,byId.rewards);
            assertEquals(jdbc.row.time,access.find(metadata,Query.where("owner",jdbc.row.owner)).getFirst().time);
            var scanned=new ArrayList<Row>();access.scan(metadata,scanned::add);assertEquals(jdbc.row.time,scanned.getFirst().time);
            assertEquals(3,jdbc.typedTimeReads);
        }
        var time=java.sql.Time.valueOf("12:34:56");time.setTime(time.getTime()+123);
        assertEquals(LocalTime.of(12,34,56,123000000),new RdbValueConverter().fromStore(time,LocalTime.class));
    }
    public static class JsonTimeRow {
        @Id public UUID id;
        @Column(type=ColumnType.JSON) public LocalTime time;
    }
    @Test void explicitJsonTimeUsesTheConfiguredCodecInsteadOfTypedJdbcTimeReads() {
        var jdbc=new Jdbc();jdbc.jsonTime=true;
        var codec=new cn.managame.core.mapping.JsonCodec() {
            public String encode(Object value,java.lang.reflect.Type type) { return "\"" + value + "\""; }
            public Object decode(String json,java.lang.reflect.Type type) { return LocalTime.parse(json.substring(1,json.length()-1)); }
        };
        var access=new RdbDataAccess(jdbc.source(),new MySqlDialect(),new EntityMapper(new RdbValueConverter(),codec));
        var metadata=EntityMetadata.inspect(JsonTimeRow.class);access.validateMapping(metadata);
        assertEquals(jdbc.row.time,access.findById(metadata,jdbc.row.id).orElseThrow().time);
        assertEquals(0,jdbc.typedTimeReads);
    }

    @Test void postgresUuidAndJsonUseTypedParametersForReadsAndAllWriteModes() {
        var jdbc=new Jdbc();var access=new RdbDataAccess(jdbc.source(),new PostgreSqlDialect(),new EntityMapper(new RdbValueConverter(),new JacksonJsonCodec()));
        var m=EntityMetadata.inspect(Row.class);var row=jdbc.row;
        access.findById(m,row.id);
        access.find(m,Query.where("rewards",row.rewards).and("id",Query.Operator.IN,List.of(row.id)));
        for (var op : List.of(WriteOperation.insert(m,row,row.id,row.owner),WriteOperation.update(m,row,row.id,row.owner),
                WriteOperation.delete(m,row.id,row.owner),WriteOperation.deleteGroup(m,row.owner))) {
            assertTrue(access.applyBatch(List.of(op)).allSuccess());
        }
        var special=jdbc.bindings.stream().filter(b->Objects.equals(b.value(),row.id.toString()) || Objects.equals(b.value(),row.owner.toString())
                || Objects.equals(b.value(),"[\"gold\"]")).toList();
        assertEquals(11,special.size());
        assertTrue(special.stream().allMatch(b->Objects.equals(Types.OTHER,b.jdbcType())));
        assertTrue(jdbc.bindings.stream().filter(b->b.value() instanceof LocalTime).allMatch(b->b.jdbcType()==null));
    }
    static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
