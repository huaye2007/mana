package cn.managame.demo;

import cn.managame.data.GameData;
import cn.managame.demo.bus.user.UserRepository;
import cn.managame.demo.bus.user.UserService;
import cn.managame.demo.common.mysql.MysqlConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
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
            // A borrowed in-memory JDBC stub prevents opening the demo's configured database.
            context.addBeanFactoryPostProcessor(factory -> {
                ((org.springframework.beans.factory.support.BeanDefinitionRegistry)factory).removeBeanDefinition("dataSource");
                factory.registerSingleton("dataSource",source);
            });
            context.register(MysqlConfig.class,UserService.class);
            context.refresh();
            data=context.getBean(GameData.class); repository=context.getBean(UserRepository.class);
            assertSame(data.repository(UserRepository.class),repository);
            var field=UserService.class.getDeclaredField("userRepository"); field.setAccessible(true);
            assertSame(repository,field.get(context.getBean(UserService.class)));
            assertNull(repository.get(1L));
        }
        assertThrows(cn.managame.data.error.DataOperationException.class,()->repository.get(2L));
    }
    private static Connection connection() {
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
