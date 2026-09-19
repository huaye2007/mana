package cn.managame.core.repository;

import cn.managame.annotation.*;
import cn.managame.core.DataException;
import cn.managame.core.GameData;
import cn.managame.core.access.Query;
import cn.managame.core.key.MapKeys;
import cn.managame.core.metadata.EntityMetadata;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupMapKeyTest {
    public static class Base {
        @Id public long id;
        @GroupKey public long owner = 1;
    }
    public static class DefaultKey extends Base { }
    public static class SingleKey extends Base { @MapKey public String slot; }
    public static class CompositeKey extends Base {
        @MapKey(order = 1) public String slot;
        @MapKey(order = 0) public int bag;
    }
    @Resident public static class ResidentKey extends Base { }
    public interface Defaults extends GroupRepository<DefaultKey, Long> { }
    public interface Singles extends GroupRepository<SingleKey, Long> { }
    public interface Composites extends GroupRepository<CompositeKey, Long> { }
    public interface Residents extends GroupRepository<ResidentKey, Long> { }

    @Test void absentMapKeyUsesPrimaryIdForLoadingAndEveryMutation() {
        var loaded = new DefaultKey(); loaded.id = 7;
        var store = new Access() {
            @Override public <T> List<T> find(EntityMetadata<T> metadata, Query query) {
                return List.of(metadata.type().cast(loaded));
            }
        };
        try (var data = new GameData(store)) {
            var repository = data.repository(Defaults.class);
            var view = repository.getGroup(1L);
            assertSame(loaded, view.get(7L));
            var replacement = new DefaultKey(); replacement.id = 7;
            repository.update(replacement); assertSame(replacement, view.get(7L));
            repository.delete(replacement); assertTrue(view.isEmpty());
            repository.insert(replacement); assertSame(replacement, view.get(7L));
            assertThrows(DataException.class, () -> repository.insert(replacement));
            var missing = new DefaultKey(); missing.id = 8;
            assertThrows(DataException.class, () -> repository.update(missing));
            repository.deleteGroup(1L); assertTrue(view.isEmpty());
            var next = new DefaultKey(); next.id = 9;
            repository.insert(next); assertSame(next, view.get(9L));
        }
    }

    @Test void singleMapKeyUsesItsOwnTypeAndChecksReplacementIdAgainstCachedEntity() {
        try (var data = new GameData(new Access())) {
            var repository = data.repository(Singles.class);
            var view = repository.getGroup(1L);
            var row = new SingleKey(); row.id = 7; row.slot = "weapon";
            repository.insert(row); assertSame(row, view.get("weapon"));
            var wrongId = new SingleKey(); wrongId.id = 8; wrongId.slot = "weapon";
            assertThrows(DataException.class, () -> repository.update(wrongId));
            assertThrows(DataException.class, () -> repository.delete(wrongId));
            assertSame(row, view.get("weapon"));
            var replacement = new SingleKey(); replacement.id = 7; replacement.slot = "weapon";
            repository.update(replacement); assertSame(replacement, view.get("weapon"));
            repository.delete(replacement); assertTrue(view.isEmpty());
        }
    }

    @Test void multipleMapKeysUseOrderedFieldValuesForAllMutations() {
        try (var data = new GameData(new Access())) {
            var repository = data.repository(Composites.class);
            var view = repository.getGroup(1L);
            var row = new CompositeKey(); row.id = 7; row.bag = 2; row.slot = "weapon";
            String key = "2:weapon";
            assertEquals(key, MapKeys.of(2, "weapon"));
            repository.insert(row); assertSame(row, view.get(key));
            assertFalse(view.containsKey(MapKeys.of("weapon", 2)));
            var duplicate = new CompositeKey(); duplicate.id = 8; duplicate.bag = 2; duplicate.slot = "weapon";
            assertThrows(DataException.class, () -> repository.insert(duplicate));
            var replacement = new CompositeKey(); replacement.id = 7; replacement.bag = 2; replacement.slot = "weapon";
            repository.update(replacement); assertSame(replacement, view.get(key));
            repository.delete(replacement); assertTrue(view.isEmpty());
            repository.insert(row); repository.deleteGroup(1L); assertTrue(view.isEmpty());
        }
    }

    @Test void residentPreloadUsesDefaultIdsAndDoesNotQueryMissingGroups() {
        var first = new ResidentKey(); first.id = 7;
        var second = new ResidentKey(); second.id = 8; second.owner = 2;
        var store = new Access() {
            @Override public <T> void scan(EntityMetadata<T> metadata, Consumer<T> consumer) {
                consumer.accept(metadata.type().cast(first)); consumer.accept(metadata.type().cast(second));
            }
            @Override public <T> List<T> find(EntityMetadata<T> metadata, Query query) {
                throw new AssertionError("resident repository must not query");
            }
        };
        try (var data = new GameData(store)) {
            var repository = data.repository(Residents.class);
            assertSame(first, repository.getGroup(1L).get(7L));
            assertSame(second, repository.getGroup(2L).get(8L));
            var empty = repository.getGroup(3L); assertTrue(empty.isEmpty());
            var third = new ResidentKey(); third.id = 9; third.owner = 3;
            repository.insert(third); assertSame(third, empty.get(9L));
        }
    }

    @Test void duplicateDefaultIdsFailLazyLoadAndResidentInitialization() {
        var store = new Access() {
            @Override public <T> List<T> find(EntityMetadata<T> metadata, Query query) {
                return List.of(metadata.newInstance(), metadata.newInstance());
            }
            @Override public <T> void scan(EntityMetadata<T> metadata, Consumer<T> consumer) {
                consumer.accept(metadata.newInstance()); consumer.accept(metadata.newInstance());
            }
        };
        try (var data = new GameData(store)) {
            assertThrows(DataException.class, () -> data.repository(Defaults.class).getGroup(1L));
            assertThrows(DataException.class, () -> data.repository(Residents.class));
        }
    }
}
