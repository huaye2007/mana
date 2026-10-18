package cn.managame.spring.data;

import cn.managame.data.GameData;
import cn.managame.data.LogRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.Id;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.mysql.Column;
import cn.managame.data.mysql.Table;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Which store each source serves, and what the integration refuses to guess. */
class StoreSourceTest {
    private static final AtomicInteger OPENED = new AtomicInteger();

    @Table(name = "spring_players")
    static class Player { @Id @Column long id; @Column int score; }

    @Table(name = "spring_logs")
    static class Entry { @Column String message; }

    @Repository
    static class Players extends SingleRepository<Long, Player> {}

    @Repository
    static class Logs extends LogRepository<Entry> {}

    static class Recording implements EntityMapper {
        final List<String> calls = new ArrayList<>();
        public void initialize(EntityMeta meta) { calls.add("initialize"); }
        public Object load(EntityMeta meta, Object id) { calls.add("load"); return null; }
        public List<?> loadGroup(EntityMeta meta, GroupKeys groupKey) { calls.add("loadGroup"); return List.of(); }
        public void insertBatch(EntityMeta meta, List<?> entities) { calls.add("insertBatch"); }
        public void updateBatch(EntityMeta meta, List<?> entities) { calls.add("updateBatch"); }
        public void deleteBatch(EntityMeta meta, List<Object> ids) { calls.add("deleteBatch"); }
        public void deleteInsertBatch(EntityMeta meta, List<?> entities) { calls.add("deleteInsertBatch"); }
    }

    /** Counts connection attempts and never opens one, so a test that connects fails instead of passing. */
    private static DataSource unopened() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getConnection" -> {
                        OPENED.incrementAndGet();
                        throw new SQLException("No database in this test");
                    }
                    case "toString" -> "unopened DataSource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Configuration
    @EnableGameData(basePackages = "cn.managame.spring.data.none")
    static class MapperOnly {
        @Bean Recording stateMapper() { return new Recording(); }
    }

    @Configuration
    @EnableGameData(basePackages = "cn.managame.spring.data.none")
    static class StateSourceOnly {
        @Bean @GameStateSource DataSource gameSource() { return unopened(); }
    }

    @Test void aStateMapperBeanCarriesStateWithoutAnyDataSource() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(MapperOnly.class, Players.class);
            context.refresh();
            // Before this, DataConfiguration took DataSource as a mandatory parameter, so a deployment whose
            // state lives in MongoDB could not start at all.
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            Players players = context.getBean(Players.class);
            assertSame(players, context.getBean(GameData.class).repository(Players.class));
            assertNull(players.get(1L));
            assertEquals(List.of("initialize", "load"), context.getBean(Recording.class).calls);
        }
    }

    @Test void aQualifiedStateSourceIsNeverOfferedToLogs() {
        OPENED.set(0);
        Throwable failure = assertThrows(Throwable.class, () -> {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(StateSourceOnly.class, Players.class, Logs.class);
                context.refresh();
            }
        });
        while (failure.getCause() != null) failure = failure.getCause();
        assertTrue(failure.getMessage().contains("logMysql(DataSource)"), failure.getMessage());
        assertEquals(0, OPENED.get(), "the refusal must come before any database is touched");
    }
}
