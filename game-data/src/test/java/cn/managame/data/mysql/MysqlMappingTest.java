package cn.managame.data.mysql;

import cn.managame.data.annotation.*;
import cn.managame.data.codec.*;
import cn.managame.data.meta.*;
import cn.managame.data.key.GroupKey;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MysqlMappingTest {
    static class Base { @Id @Column long id; }
    @Table(name="tasks",indexes=@Index(columnList="role_id, task_id"))
    static class Task extends Base {
        @cn.managame.data.annotation.GroupKey @Column long roleId;
        @MapKey @Column long taskId;
        @Column int progress;
        @Column String description;
        String ignored;
    }
    @Table(name="complex")
    static class Complex extends Base {
        @Column(type=ColumnType.JSON) List<String> data;
        @Column(type=ColumnType.JSON) String raw;
        @Column(type=ColumnType.BINARY) byte[] bytes;
    }
    static final class Access implements MysqlAccess {
        final JdbcMysqlAccess jdbc;
        final List<String> ddl = new ArrayList<>();
        boolean exists;
        String idType = "bigint";
        Access() {
            var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            jdbc = new JdbcMysqlAccess(source);
        }
        public <T> T queryOne(String sql,Object[] args,RowMapper<T> mapper) {
            if (sql.contains("information_schema.tables")) return row(mapper,exists ? 1L : 0L);
            return jdbc.queryOne(sql,args,mapper);
        }
        public <T> List<T> query(String sql,Object[] args,RowMapper<T> mapper) {
            if (sql.contains("information_schema.statistics")) return List.of(row(mapper,"PRIMARY",0,1,"id"));
            if (sql.contains("information_schema.columns")) return List.of(row(mapper,"id",idType,"NO"));
            return jdbc.query(sql,args,mapper);
        }
        public int update(String sql,Object[] args) { ddl.add(sql); return jdbc.update(sql,args); }
        public int[] batchUpdate(String sql,List<Object[]> args) { return jdbc.batchUpdate(sql,args); }
        public <T> T transaction(TransactionAction<T> action) { return jdbc.transaction(action); }
    }
    static <T> T row(RowMapper<T> mapper,Object... values) {
        ResultSet row = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},
                (proxy,method,args) -> {
                    if (method.getName().equals("wasNull")) return false;
                    Object value = values[(Integer)args[0]-1];
                    return switch (method.getName()) {
                        case "getLong" -> ((Number)value).longValue();
                        case "getInt" -> ((Number)value).intValue();
                        case "getString" -> value.toString();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        try { return mapper.map(row); } catch (SQLException e) { throw new AssertionError(e); }
    }
    @Test void mapperRoundTripFullStateUpdateAndTransactionalReplacement() {
        var access = new Access(); var mapper = new MysqlEntityMapper(access);
        var meta = new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta);
        var t = new Task(); t.id=1; t.roleId=4; t.taskId=7; t.progress=10; t.description="old";
        mapper.insertBatch(meta,List.of(t));
        var loaded = (Task)mapper.load(meta,1L); assertEquals(10,loaded.progress); assertEquals("old",loaded.description);
        assertEquals(1,mapper.loadGroup(meta,GroupKey.of(4L)).size());
        t.progress=20; t.roleId=99; mapper.updateBatch(meta,List.of(t));
        loaded=(Task)mapper.load(meta,1L); assertEquals(4,loaded.roleId); assertEquals(20,loaded.progress);
        t.roleId=4;
        assertThrows(MysqlException.class, () -> mapper.deleteInsertBatch(meta,List.of(t,t)));
        assertEquals(20,((Task)mapper.load(meta,1L)).progress);
        t.progress=30; mapper.deleteInsertBatch(meta,List.of(t));
        assertEquals(30,((Task)mapper.load(meta,1L)).progress);
        mapper.deleteBatch(meta,List.of(1L)); assertNull(mapper.load(meta,1L));
        assertTrue(access.ddl.stream().anyMatch(s -> s.startsWith("CREATE INDEX")));
    }
    @Test void schemaAddsOnlyMissingColumnsAndExplicitIndexes() {
        var access = new Access();
        access.jdbc.update("CREATE TABLE tasks (id BIGINT PRIMARY KEY)",new Object[0]); access.exists=true;
        new MysqlEntityMapper(access).initialize(new EntityMeta(Task.class,EntityMeta.Kind.GROUP));
        assertEquals(4,access.ddl.stream().filter(s -> s.contains("ADD COLUMN")).count());
        assertEquals(1,access.ddl.stream().filter(s -> s.startsWith("CREATE INDEX")).count());
        assertTrue(access.ddl.stream().noneMatch(s -> s.contains("DROP") || s.contains("CHANGE")));
        access.idType="varchar";
        assertThrows(IllegalArgumentException.class, () -> new MysqlEntityMapper(access).initialize(new EntityMeta(Task.class,EntityMeta.Kind.GROUP)));
    }
    @Test void codecReceivesGenericTypeAndRawFieldsBypassSerialization() throws Exception {
        var encoded = new ArrayList<Object>(); var types = new ArrayList<Type>();
        JsonCodec json = new JsonCodec() {
            public String encode(Object value) { encoded.add(value); return "[\"x\"]"; }
            public Object decode(String value,Type type) { types.add(type); return List.of("x"); }
        };
        var meta = new EntityMeta(Complex.class,EntityMeta.Kind.SINGLE);
        assertThrows(IllegalArgumentException.class, () -> new MysqlEntityMeta(meta,null,null));
        var mapping = new MysqlEntityMeta(meta,json,null);
        var value = new Complex(); value.id=1; value.data=List.of("x"); value.raw="{\"a\":1}"; value.bytes=new byte[]{1,2};
        var args = MysqlEntityMeta.arguments(mapping.fields,value);
        assertEquals(1,encoded.size());
        var raw = mapping.fields.stream().filter(f -> f.name.equals("raw")).findFirst().orElseThrow();
        assertEquals(value.raw,raw.encode(value));
        var bytes = mapping.fields.stream().filter(f -> f.name.equals("bytes")).findFirst().orElseThrow();
        assertSame(value.bytes,bytes.encode(value));
        var field = mapping.fields.stream().filter(f -> f.name.equals("data")).findFirst().orElseThrow();
        row(r -> { field.read(value,r,1); return null; },"[\"x\"]");
        assertInstanceOf(ParameterizedType.class,types.getFirst());
        assertEquals(String.class,((ParameterizedType)types.getFirst()).getActualTypeArguments()[0]);
    }
}
