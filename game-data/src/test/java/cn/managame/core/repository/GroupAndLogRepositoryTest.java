package cn.managame.core.repository;

import cn.managame.core.DataException;
import cn.managame.core.GameData;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.support.DataTestSupport.*;
import static cn.managame.core.repository.RepositoryFactoryTest.*;

class GroupAndLogRepositoryTest {
    @Test void groupMutationsUseTheSameCachedMap() {
        try (var game = RepositoryFactoryTest.game(new Access())) {
            var groups = game.repository(Groups.class);
            var view = groups.getGroup(1L);
            var first = new Row(1); groups.insert(first);
            assertSame(view, groups.getGroup(1L));
            assertSame(first, view.get(1L));
            assertSame(first, view.remove(1L));
            assertTrue(groups.getGroup(1L).isEmpty());
            view.put(1L, first);
            var replacement = new Row(1); replacement.value = 9; groups.update(replacement);
            assertSame(view, groups.getGroup(1L));
            assertSame(replacement, view.get(1L));
            assertSame(view, game.repository(AliasGroups.class).getGroup(1L));
            groups.delete(replacement); assertTrue(view.isEmpty());
            groups.insert(new Row(2)); groups.deleteGroup(1L); assertTrue(view.isEmpty());
            groups.insert(new Row(3)); assertEquals(1, view.size()); assertTrue(view.containsKey(3L));
        }
    }

    @Test void loadedGroupAndMatchingCacheEntryAreRequired() {
        try (var game = RepositoryFactoryTest.game(new Access())) {
            var groups = game.repository(Groups.class);
            assertThrows(DataException.class, () -> groups.insert(new Row(1)));
            groups.getGroup(1L);
            var row = new Row(1); groups.insert(row);
            assertThrows(DataException.class, () -> groups.insert(new Row(1)));
            assertThrows(DataException.class, () -> groups.update(new Row(2)));
            assertThrows(DataException.class, () -> groups.delete(new Row(2)));
            assertSame(row, groups.getGroup(1L).get(1L));
        }
    }

    @Test void largeGroupUpdatesRemainVisibleWithoutCopyingTheGroup() {
        try (var game = GameData.builder().dataAccess("game", new Access()).ensureSchema(false).build()) {
            var groups = game.repository(Groups.class);
            var view = groups.getGroup(1L);
            for (int i = 0; i < 5000; i++) groups.insert(new Row(i));
            for (Row row : view.values()) { row.value++; groups.update(row); }
            assertSame(view, groups.getGroup(1L));
            assertEquals(5000, view.size());
            assertTrue(view.values().stream().allMatch(row -> row.value == 1));
        }
    }

    @Test void fixedLogsAppendToPrecreatedDefaultTableWithoutRuntimeDdl() {
        var access = new Access();
        try (var game = new GameData(access)) {
            game.repository(Logs.class).append(new Row(1));
            game.repository(Logs.class).append(new Row(2));
            game.flushLogs();
            assertEquals(0, access.schemaCalls.get());
            assertEquals(2, access.saved.size());
        }
    }
}
