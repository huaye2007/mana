package cn.managame.data.mysql;

import cn.managame.data.annotation.*;
import cn.managame.data.codec.*;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.*;
import cn.managame.data.key.GroupKeys;
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
        @GroupKey @Column long roleId;
        @MapKey @Column long taskId;
        @Column int progress;
        @Column String description;
        transient String ignored;   // transient is what keeps a field out (D-MAP-01)
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
        var meta = new EntityMeta(Task.class,EntityMeta.Kind.GROUP); mapper.initialize(meta,SchemaMode.CREATE);
        var t = new Task(); t.id=1; t.roleId=4; t.taskId=7; t.progress=10; t.description="old";
        mapper.insertBatch(meta,List.of(t));
        var loaded = (Task)mapper.load(meta,1L); assertEquals(10,loaded.progress); assertEquals("old",loaded.description);
        assertEquals(1,mapper.loadGroup(meta,GroupKeys.of(4L)).size());
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
    @Table(name="plain")
    static class Plain {
        @Id long id;
        int level; String name; @Column(length=32) String tag;
        Map<Integer,Long> items = new java.util.concurrent.ConcurrentHashMap<>();
        transient int cachedPower; static int instances;
    }
    @Table(name="final_field")
    static class FinalField { @Id long id; final List<Long> ids = new ArrayList<>(); }
    @Table(name="bad_length")
    static class BadLength { @Id long id; @Column(length=10) int level; }
    private static MysqlFieldMeta column(MysqlEntityMeta mapping, String name) {
        return mapping.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
    }
    @Test void everyInstanceFieldIsAColumnWithoutAnnotations() {
        var mapping = new MysqlEntityMeta(new EntityMeta(Plain.class,EntityMeta.Kind.SINGLE),null,null);
        // Forgetting an annotation can no longer leave a field unsaved; transient and static stay out (D-MAP-01).
        assertEquals(Set.of("id","level","name","tag","items"), mapping.fields.stream().map(f -> f.name).collect(java.util.stream.Collectors.toSet()));
        assertEquals("BIGINT", column(mapping,"id").sqlType); assertEquals("INT", column(mapping,"level").sqlType);
        assertEquals("VARCHAR(255)", column(mapping,"name").sqlType); assertEquals("VARCHAR(32)", column(mapping,"tag").sqlType);
        assertEquals("JSON", column(mapping,"items").sqlType);   // a collection needs no type to be stored
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new MysqlEntityMeta(new EntityMeta(FinalField.class,EntityMeta.Kind.SINGLE),null,null));
        assertTrue(failure.getMessage().contains("transient"), failure.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new MysqlEntityMeta(new EntityMeta(BadLength.class,EntityMeta.Kind.SINGLE),null,null));
    }
    /** Takes the multi-row path, records each statement, and matches every listed row but {@code missing}. */
    static final class MultiRowAccess implements MysqlAccess {
        final List<String> sql = new ArrayList<>(); final List<Object[]> args = new ArrayList<>();
        int missing;
        static int rows(String statement) {
            return (int) statement.substring(statement.indexOf(" IN (")).chars().filter(c -> c == '?').count();
        }
        @Override public boolean supportsMultiRowUpdate() { return true; }
        public int update(String s,Object[] a) { sql.add(s); args.add(a); return rows(s) - missing; }
        public <T> T queryOne(String s,Object[] a,RowMapper<T> m) { throw new UnsupportedOperationException(s); }
        public <T> List<T> query(String s,Object[] a,RowMapper<T> m) { throw new UnsupportedOperationException(s); }
        public int[] batchUpdate(String s,List<Object[]> a) { throw new UnsupportedOperationException(s); }
        public <T> T transaction(TransactionAction<T> action) {
            try { return action.execute(this); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
    @Table(name="scores")
    static class Score {
        @Id long id; int points; String note;
        Score() {}
        Score(long id,int points) { this.id=id; this.points=points; note="n"+id; }
    }
    @Test void multiRowUpdatesListEveryRowOnceAndFailTheBatchWhenOneIsMissing() {
        var access = new MultiRowAccess(); var mapper = new MysqlEntityMapper(access);
        var meta = new EntityMeta(Score.class,EntityMeta.Kind.SINGLE); mapper.initialize(meta,SchemaMode.NONE);
        mapper.updateBatch(meta,List.of(new Score(1,10),new Score(2,20)));
        assertEquals(List.of("UPDATE `scores` SET `points` = CASE `id` WHEN ? THEN ? WHEN ? THEN ? END, "
                + "`note` = CASE `id` WHEN ? THEN ? WHEN ? THEN ? END WHERE `id` IN (?, ?)"), access.sql);
        assertArrayEquals(new Object[]{1L,10,2L,20,1L,"n1",2L,"n2",1L,2L}, access.args.getFirst());
        access.sql.clear();
        var many = new ArrayList<Score>(); for (long id=1; id<=250; id++) many.add(new Score(id,1));
        mapper.updateBatch(meta,many);
        assertEquals(List.of(100,100,50), access.sql.stream().map(MultiRowAccess::rows).toList());
        access.missing = 1;   // a row that is not stored fails the statement, so the pipeline splits the batch
        var failure = assertThrows(MysqlException.class,()->mapper.updateBatch(meta,many.subList(0,3)));
        assertEquals("02000", failure.sqlState());
        // A wide table takes fewer rows per statement, so one statement stays under the placeholder limit.
        assertEquals(100, MysqlEntityMeta.updateRows(299)); assertEquals(99, MysqlEntityMeta.updateRows(300));
        assertEquals(1, MysqlEntityMeta.updateRows(40_000));
    }
    @Table(name="unsafe")
    static class Unsafe { @Id long id; List<Long> ids = new ArrayList<>(); Map<Integer,Integer> counts; }
    @Test void collectionsTheSaveThreadCannotReadSafelyAreNamedAtStartup() {
        var logger = java.util.logging.Logger.getLogger(MysqlEntityMeta.class.getName());
        var warnings = new ArrayList<String>();
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) warnings.add(record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            new MysqlEntityMeta(new EntityMeta(Unsafe.class,EntityMeta.Kind.SINGLE),null,null);
            assertEquals(2, warnings.size(), warnings.toString());
            assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Unsafe.ids is a java.util.ArrayList")), warnings.toString());
            assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Unsafe.counts is not initialized")), warnings.toString());
            warnings.clear();
            new MysqlEntityMeta(new EntityMeta(Plain.class,EntityMeta.Kind.SINGLE),null,null);   // a ConcurrentHashMap
            assertEquals(List.of(), warnings);
        } finally { logger.removeHandler(handler); }
    }
    @Test void schemaAddsOnlyMissingColumnsAndExplicitIndexes() {
        var access = new Access();
        access.jdbc.update("CREATE TABLE tasks (id BIGINT PRIMARY KEY)",new Object[0]); access.exists=true;
        new MysqlEntityMapper(access).initialize(new EntityMeta(Task.class,EntityMeta.Kind.GROUP),SchemaMode.CREATE);
        assertEquals(4,access.ddl.stream().filter(s -> s.contains("ADD COLUMN")).count());
        assertEquals(1,access.ddl.stream().filter(s -> s.startsWith("CREATE INDEX")).count());
        assertTrue(access.ddl.stream().noneMatch(s -> s.contains("DROP") || s.contains("CHANGE")));
        access.idType="varchar";
        assertThrows(IllegalArgumentException.class, () -> new MysqlEntityMapper(access).initialize(new EntityMeta(Task.class,EntityMeta.Kind.GROUP),SchemaMode.CREATE));
    }
    @Test void codecReceivesGenericTypeAndRawFieldsBypassSerialization() throws Exception {
        var encoded = new ArrayList<Object>(); var types = new ArrayList<Type>();
        JsonCodec json = new JsonCodec() {
            public String encode(Object value) { encoded.add(value); return "[\"x\"]"; }
            public Object decode(String value,Type type) { types.add(type); return List.of("x"); }
        };
        var meta = new EntityMeta(Complex.class,EntityMeta.Kind.SINGLE);
        assertDoesNotThrow(() -> new MysqlEntityMeta(meta,null,null));
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

    static class Role { private long id; private List<Integer> levels; }
    @Table(name="json_users")
    static class JsonUser extends Base {
        @Column(type=ColumnType.JSON) private Map<Integer,Long> serverRoleIdMap = new java.util.concurrent.ConcurrentHashMap<>();
        @Column(type=ColumnType.JSON) private Map<Long,List<Role>> roles;
        @Column(type=ColumnType.JSON) private List<Long> ids = new ArrayList<>();
        @Column(type=ColumnType.JSON) private Set<Integer> levels = new LinkedHashSet<>();
    }
    @Test void defaultJsonBindsMapKeysValuesAndNestedPrivateObjectsAtInitialization() {
        var mapping = new MysqlEntityMeta(new EntityMeta(JsonUser.class,EntityMeta.Kind.SINGLE),null,null);
        var original = new JsonUser(); original.id=1; original.serverRoleIdMap=Map.of(2,3L);
        var role = new Role(); role.id=4; role.levels=List.of(5,6); original.roles=Map.of(7L,List.of(role));
        original.ids.add(8L); original.levels.add(9);
        var loaded = new JsonUser();
        for (var field : mapping.fields) if (!field.identity)
            row(r -> { field.read(loaded,r,1); return null; },field.encode(original));
        assertEquals(Map.of(2,3L),loaded.serverRoleIdMap);
        assertInstanceOf(java.util.concurrent.ConcurrentHashMap.class,loaded.serverRoleIdMap);
        assertInstanceOf(Integer.class,loaded.serverRoleIdMap.keySet().iterator().next());
        assertInstanceOf(Long.class,loaded.serverRoleIdMap.get(2));
        Role decoded=loaded.roles.get(7L).getFirst(); assertEquals(4,decoded.id); assertEquals(List.of(5,6),decoded.levels);
        assertInstanceOf(ArrayList.class,loaded.ids); assertEquals(List.of(8L),loaded.ids);
        assertInstanceOf(LinkedHashSet.class,loaded.levels); assertEquals(Set.of(9),loaded.levels);
        var field=mapping.fields.stream().filter(f->f.name.equals("server_role_id_map")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->row(r->{ field.read(loaded,r,1); return null; },"{broken"));
        assertThrows(IllegalArgumentException.class,()->row(r->{ field.read(loaded,r,1); return null; },"{} {}"));
    }
}
