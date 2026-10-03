package cn.managame.demo;

import cn.managame.data.GameData;
import cn.managame.demo.bus.user.UserRepository;
import cn.managame.demo.bus.user.UserService;
import cn.managame.demo.common.mysql.MysqlConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DataSpringWiringTest {
    @Test void springInjectsTheInitializedRepositoryFromGameData() throws Exception {
        DataSource source=(DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(p,m,a)-> {
            if(m.getName().equals("getConnection")) return connection();
            throw new UnsupportedOperationException(m.getName());
        });
        GameData data;
        UserRepository repository;
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-database", Map.of(
                    "game.db.url", "jdbc:mysql://localhost/demo_test",
                    "game.db.username", "demo_test_user",
                    "game.db.password", "test:password")));
            // A borrowed in-memory JDBC stub prevents opening the demo's configured database.
            context.addBeanFactoryPostProcessor(factory -> {
                ((org.springframework.beans.factory.support.BeanDefinitionRegistry)factory).removeBeanDefinition("dataSource");
                factory.registerSingleton("dataSource",source);
            });
            context.scan("cn.managame.demo");
            context.refresh();
            assertTrue(context.getBeansOfType(cn.managame.demo.examples.runtime.RuntimeHttpExample.Methods.class).isEmpty(),
                    "Standalone HTTP sample must not join the main application's Handler discovery");
            MysqlConfig config = context.getBean(MysqlConfig.class);
            assertEquals("jdbc:mysql://localhost/demo_test", config.getUrl());
            assertEquals("demo_test_user", config.getUsername());
            assertEquals("test:password", config.getPassword());
            assertNotNull(context.getEnvironment().getPropertySources().get("class path resource [application.properties]"));
            data=context.getBean(GameData.class); repository=context.getBean(UserRepository.class);
            assertEquals(1, context.getBeansOfType(UserRepository.class).size());
            assertSame(repository, context.getBean("userRepository"));
            assertSame(data.repository(UserRepository.class),repository);
            var field=UserService.class.getDeclaredField("userRepository"); field.setAccessible(true);
            assertSame(repository,field.get(context.getBean(UserService.class)));
            assertNull(repository.get(1L));
        }
        assertThrows(cn.managame.data.error.DataOperationException.class,()->repository.get(2L));
    }
    @Test void missingJdbcUrlFailsBeforeOpeningAPool() {
        var config = new MysqlConfig();
        assertTrue(assertThrows(IllegalArgumentException.class, config::dataSource).getMessage().contains("game.db.url"));
    }
    @Test void applicationInitializationReturnsBeforeListenerStartupAndContextOwnsClosure() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            // Bootstrap returns so main can start TCP; HTTP is managed by game-spring (disabled in this test).
            GameDemo.run(context);
            assertTrue(context.isActive());
            context.close();
            assertFalse(context.isActive());
        }
    }
    static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()) {
            case "prepareStatement" -> statement((String)a[0]);
            case "close" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    private static PreparedStatement statement(String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->switch(m.getName()) {
            case "setObject", "clearParameters", "close" -> null;
            case "executeUpdate" -> 0;
            case "executeQuery" -> rows(sql);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    private static ResultSet rows(String sql) {
        boolean[] read={false};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()) {
            case "next" -> { boolean next=!read[0] && sql.contains("information_schema"); read[0]=true; yield next; }
            case "getLong" -> 0L;
            case "getInt" -> (Integer)a[0]==3 ? 1 : 0;
            case "getString" -> (Integer)a[0]==1 ? "PRIMARY" : "user_id";
            case "close" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
}
