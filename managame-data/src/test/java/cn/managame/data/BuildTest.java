package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.Fixtures.ItemLogs;
import cn.managame.data.Fixtures.Players;
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
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(database, NoIds.class).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(database, FinalFields.class).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(database, WronglyKeyed.class).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(database, Groups.class).build());
    }

    @Test
    void refusesRepositoriesRegisteredWrongly() {
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(database, ItemLogs.class).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().logRepositories((type, logs) -> {}, Players.class).build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().repositories(database, Players.class).repositories(database, Players.class));
        assertThrows(IllegalStateException.class, () -> GameData.builder().build());
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().maxCacheEntries(0));
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().cacheExpire(Duration.ZERO));
    }

    @Test
    void passesOnWhatAMapperThrowsWhilePreparing() {
        IllegalStateException refused = new IllegalStateException("no table for Player");
        EntityMapper refusing = new MemoryMapper() {
            @Override public synchronized void prepare(EntityType type) { throw refused; }
        };
        assertSame(refused, assertThrows(IllegalStateException.class, () -> GameData.builder().repositories(refusing, Players.class).build()));
    }

    @Test
    void takesRepositoriesAsACollection() {
        List<Class<?>> scanned = List.of(Players.class);   // as a container integration finds them
        try (GameData data = GameData.builder().repositories(database, scanned).build()) {
            assertEquals(Players.class, data.repository(Players.class).getClass());
        }
    }
}
