package cn.managame.demo;

import cn.managame.data.GameData;
import cn.managame.data.GroupRepository;
import cn.managame.data.LogRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.error.DataOperationException;
import cn.managame.data.mysql.Column;
import cn.managame.data.mysql.Table;
import cn.managame.demo.common.data.GameDataConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DataRepositoryRegistrationTest {
    @Test void registersAllRepositoryKindsAndAppliesSpringInjectionToTheInitializedInstance() {
        Players.constructed.set(0);
        Players players;
        try (var context = context()) {
            context.register(GameDataConfig.class, Players.class, Tasks.class, Logs.class, Marker.class);
            context.refresh();
            var data = context.getBean(GameData.class);
            players = context.getBean(Players.class);
            assertSame(players, data.repository(Players.class));
            assertSame(players, context.getBean("players"));
            assertSame(context.getBean(Marker.class), players.marker);
            assertEquals(1, Players.constructed.get());
            assertSame(context.getBean(Tasks.class), data.repository(Tasks.class));
            assertSame(context.getBean(Logs.class), data.repository(Logs.class));
        }
        assertThrows(DataOperationException.class, () -> players.get(2L));
    }

    @Test void rejectsPrototypeRepositoriesBeforeOpeningDatabaseConnections() {
        try (var context = context()) {
            context.register(GameDataConfig.class, PrototypePlayers.class);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, context::refresh);
            assertTrue(failure.getMessage().contains("singleton"));
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("repository-binding-tests");
        context.registerBean(DataSource.class, () -> (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class[]{DataSource.class}, (p, m, a) -> {
                    if (m.getName().equals("getConnection")) return DataSpringWiringTest.connection();
                    throw new UnsupportedOperationException(m.getName());
                }));
        return context;
    }

    static class Marker {}

    @Table(name = "binding_players")
    static class Player { @Id @Column(name = "user_id") long id; }

    @Table(name = "binding_tasks")
    static class Task extends Player {
        @GroupKey @Column long owner;
        @MapKey @Column int task;
    }

    @Table(name = "binding_logs")
    static class Entry { @Column String message; }

    @Repository("players") @Profile("repository-binding-tests")
    static class Players extends SingleRepository<Long, Player> {
        static final AtomicInteger constructed = new AtomicInteger();
        Marker marker;
        Players() { constructed.incrementAndGet(); }
        @Autowired void configure(Marker marker) {
            this.marker = marker;
            assertNull(get(1L)); // Data initialization must precede Spring injection callbacks.
        }
    }

    @Repository @Profile("repository-binding-tests")
    static class Tasks extends GroupRepository<Integer, Task> {}

    @Repository @Profile("repository-binding-tests")
    static class Logs extends LogRepository<Entry> {}

    @Repository @Profile("repository-binding-tests") @Scope("prototype")
    static class PrototypePlayers extends SingleRepository<Long, Player> {}
}
