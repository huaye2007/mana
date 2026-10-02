package cn.managame.data;

import cn.managame.data.annotation.Id;
import cn.managame.data.codec.JsonCodec;
import cn.managame.data.error.DataLoadException;
import cn.managame.data.mysql.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class MysqlBuilderTest {
    @Table(name="builder_users")
    static class User {
        @Id @Column long id;
        @Column(type=ColumnType.JSON) Map<Integer,Long> roles = new java.util.concurrent.ConcurrentHashMap<>();
    }
    static class Users extends SingleRepository<Long,User> {}
    @Table(name="builder_logs")
    static class Log { @Column(type=ColumnType.JSON) Map<Integer,Long> roles; }
    static class Logs extends LogRepository<Log> {}

    /** H2 stores JSON as CLOB here; MySQL schema metadata queries are adapted, not the Data API. */
    static class Source implements DataSource, AutoCloseable {
        final JdbcDataSource delegate=new JdbcDataSource();
        boolean closed; int opened, released;
        Source() { delegate.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"); }
        public Connection getConnection() throws SQLException {
            opened++; Connection connection=delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                if (method.getName().equals("prepareStatement")) {
                    String sql=(String)args[0];
                    if(sql.contains("information_schema.tables")) args[0]="SELECT 0 WHERE ? IS NOT NULL";
                    else if(sql.contains("information_schema.statistics")) args[0]="SELECT 'PRIMARY', 0, 1, 'id' WHERE ? IS NOT NULL";
                    else args[0]=sql.replace(" JSON", " CLOB");
                }
                try { return method.invoke(connection,args); }
                catch(InvocationTargetException cause) { throw cause.getCause(); }
                finally { if(method.getName().equals("close")) released++; }
            });
        }
        public Connection getConnection(String user,String password) throws SQLException { return getConnection(); }
        public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
        public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        public Logger getParentLogger() { return Logger.getLogger("test"); }
        public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
        public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
        public void close() { closed=true; }
        void execute(String sql,Object... args) { new JdbcMysqlAccess(this).update(sql,args); }
        String json(long id) { return new JdbcMysqlAccess(this).queryOne("SELECT roles FROM builder_users WHERE id = ?",new Object[]{id},r->r.getString(1)); }
    }

    @Test void dataSourceEntryLoadsTypedJsonFlushesChangesAndKeepsSourceBorrowed() {
        var source=new Source();
        var data=GameDataBuilder.builder().repositories(Users.class).mysql(source).flushInterval(Duration.ofHours(1)).cacheExpire(Duration.ofHours(2)).build();
        try {
            source.execute("INSERT INTO builder_users VALUES (?, ?)",1L,"{\"2\":3}");
            User user=data.repository(Users.class).get(1L); assertEquals(Map.of(2,3L),user.roles);
            assertInstanceOf(java.util.concurrent.ConcurrentHashMap.class,user.roles);
            user.roles=Map.of(4,5L); data.repository(Users.class).update(user);
        } finally { data.close(); }
        assertEquals("{\"4\":5}",source.json(1)); assertFalse(source.closed); assertEquals(source.opened,source.released);
    }

    @Test void customCodecAppliesToConvenienceStateAndLogBindingsRegardlessOfSetterOrder() {
        var source=new Source(); var types=new ArrayList<Type>();
        JsonCodec codec=new JsonCodec() {
            public String encode(Object value) { return "custom"; }
            public Object decode(String value,Type type) { types.add(type); return Map.of(6,7L); }
        };
        source.execute("CREATE TABLE builder_logs (roles CLOB)");
        try(var data=GameDataBuilder.builder().mysql(source).repositories(List.of(Users.class)).logRepositories(List.of(Logs.class))
                .jsonCodec(codec).flushInterval(Duration.ofHours(1)).cacheExpire(Duration.ofHours(2)).build()) {
            source.execute("INSERT INTO builder_users VALUES (?, ?)",1L,"custom");
            var user=data.repository(Users.class).get(1L); assertEquals(Map.of(6,7L),user.roles);
            data.repository(Users.class).update(user); var log=new Log(); log.roles=user.roles; data.repository(Logs.class).insert(log);
        }
        assertEquals("custom",source.json(1));
        assertEquals("custom",new JdbcMysqlAccess(source).queryOne("SELECT roles FROM builder_logs",new Object[0],r->r.getString(1)));
        assertEquals(User.class.getDeclaredFields()[1].getGenericType(),types.getFirst()); assertFalse(source.closed);
    }

    @Test void invalidJsonIsLoadFailureAndCanBeRetriedAfterStorageIsCorrected() {
        var source=new Source();
        try(var data=GameDataBuilder.builder().mysql(source).repositories(Users.class).build()) {
            source.execute("INSERT INTO builder_users VALUES (?, ?)",1L,"broken");
            assertThrows(DataLoadException.class,()->data.repository(Users.class).get(1L));
            source.execute("UPDATE builder_users SET roles = ? WHERE id = ?","{\"2\":3}",1L);
            assertEquals(Map.of(2,3L),data.repository(Users.class).get(1L).roles);
        }
    }

    @Test void defaultCodecAlsoWritesJsonLogsWithoutRegistration() {
        var source=new Source(); source.execute("CREATE TABLE builder_logs (roles CLOB)");
        try(var data=GameDataBuilder.builder().mysql(source).logRepositories(Logs.class).build()) {
            var log=new Log(); log.roles=Map.of(8,9L); data.repository(Logs.class).insert(log);
        }
        assertEquals("{\"8\":9}",new JdbcMysqlAccess(source).queryOne("SELECT roles FROM builder_logs",new Object[0],r->r.getString(1)));
    }

    @Test void logCodecOverrideDoesNotChangeStateCodec() {
        var source=new Source(); source.execute("CREATE TABLE builder_logs (roles CLOB)");
        JsonCodec logCodec=new JsonCodec() {
            public String encode(Object value) { return "log"; }
            public Object decode(String value,Type type) { throw new AssertionError("Logs are not loaded"); }
        };
        try(var data=GameDataBuilder.builder().logCodecs(logCodec,null).mysql(source).repositories(Users.class).logRepositories(Logs.class).build()) {
            var user=new User(); user.id=1; user.roles.put(2,3L); data.repository(Users.class).insert(user);
            var log=new Log(); log.roles=user.roles; data.repository(Logs.class).insert(log);
        }
        assertEquals("{\"2\":3}",source.json(1));
        assertEquals("log",new JdbcMysqlAccess(source).queryOne("SELECT roles FROM builder_logs",new Object[0],r->r.getString(1)));
    }

    @Test void missingSourceAndMappingFailureDoNotTakeOwnershipOfSource() {
        assertThrows(NullPointerException.class,()->GameDataBuilder.builder().mysql(null));
        assertThrows(IllegalArgumentException.class,()->GameDataBuilder.builder().repositories(Users.class).build());
        assertThrows(IllegalArgumentException.class,()->GameDataBuilder.builder().logRepositories(Logs.class).build());
        var source=new Source();
        assertThrows(IllegalArgumentException.class,()->GameDataBuilder.builder().mysql(source).repositories(Users.class,Users.class).build());
        assertEquals(0,source.opened); assertFalse(source.closed);
    }
}
