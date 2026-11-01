package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.Fixtures.ItemLogs;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** build checks every repository and entity class, and lets each mapper prepare, before anything starts. */
class BuildTest {
    private final MemoryMapper database = new MemoryMapper();

    static class NoId {
        long id;
    }

    static class FinalField {
        @Id long id;
        final String name = "";
    }

    static class GroupWithoutMapKey {
        @Id long id;
        @GroupKey long playerId;
    }

    static final class NoIds extends SingleRepository<Long, NoId> {}

    static final class FinalFields extends SingleRepository<Long, FinalField> {}

    static final class WronglyKeyed extends SingleRepository<Integer, Fixtures.Player> {}

    static final class Groups extends GroupRepository<Long, GroupWithoutMapKey> {}

    @Test
    void refusesEntityClassesThatDoNotFit() {
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(database, List.of(NoIds.class)).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(database, List.of(FinalFields.class)).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(database, List.of(WronglyKeyed.class)).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(database, List.of(Groups.class)).build());
    }

    @Test
    void refusesRepositoriesRegisteredWrongly() {
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(database, List.of(ItemLogs.class)).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).logRepositories((type, logs) -> {}, List.of(Players.class)).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(database, List.of(Players.class)).repositories(database, List.of(Players.class)));
        assertThrows(IllegalStateException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).build());
        assertThrows(IllegalStateException.class, () -> GameData.builder().repositories(database, List.of(Players.class)).build(), "no errorHandler");
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().cacheExpire(Duration.ZERO));
    }

    @Test
    void passesOnWhatAMapperThrowsWhilePreparing() {
        IllegalStateException refused = new IllegalStateException("no table for Player");
        EntityMapper refusing = new MemoryMapper() {
            @Override public synchronized void prepare(EntityType type) { throw refused; }
        };
        assertSame(refused, assertThrows(IllegalStateException.class, () -> GameData.builder().errorHandler(new ReportedFailures()).repositories(refusing, List.of(Players.class)).build()));
    }

    @Test
    void takesRepositoriesAsACollection() {
        List<Class<?>> scanned = List.of(Players.class);   // as a container integration finds them
        try (GameData data = GameData.builder().errorHandler(new ReportedFailures()).repositories(database, scanned).build()) {
            assertEquals(Players.class, data.repository(Players.class).getClass());
        }
    }
}
