package cn.managame.core.key;

import cn.managame.annotation.GroupKey;
import cn.managame.annotation.Id;
import cn.managame.core.DataException;
import cn.managame.core.GameData;
import cn.managame.core.access.Query;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.repository.GroupRepository;
import cn.managame.core.write.BatchResult;
import cn.managame.core.write.WriteOperation;
import cn.managame.core.write.WriteType;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyConstructionTest {
    public static class Row {
        @Id public long id;
        @GroupKey(order = 1) public int activity;
        @GroupKey(order = 0) public long player;
    }
    public interface Rows extends GroupRepository<Row, Long> { }
    public static class Single {
        @Id public long id;
        @GroupKey public UUID owner;
    }
    enum Kind { DAILY; @Override public String toString() { return "display"; } }
    public static class Scalars {
        @Id public long id;
        @GroupKey(order = 0) public Kind kind;
        @GroupKey(order = 1) public UUID owner;
        @GroupKey(order = 2) public boolean active;
        @GroupKey(order = 4) public String tag;
        @GroupKey(order = 3) public LocalDate day;
    }

    @Test void singleValuesKeepTheirTypeAndCompositeValuesAreStrings() {
        for (Object value : List.of(10001L, 7, "name:with:colon", UUID.randomUUID(), Kind.DAILY)) {
            assertSame(value, GroupKeys.of(value));
            assertSame(value, MapKeys.of(value));
        }
        assertEquals("10001:7:weapon", GroupKeys.of(10001L, 7, "weapon"));
        assertEquals("10001:7:weapon", MapKeys.of(10001L, 7, "weapon"));
        assertEquals("DAILY:", GroupKeys.of(Kind.DAILY, ""));
        var single = new Single(); single.owner = UUID.randomUUID();
        var metadata = EntityMetadata.inspect(Single.class);
        assertSame(single.owner, metadata.groupKey(single));
        assertSame(single.owner, metadata.groupKeyValues(single.owner).getFirst());
    }

    @Test void repositoryLoadsAndDeletesUsingStringKeyAndTypedQueryValues() {
        var row = new Row(); row.id = 1; row.player = 10001; row.activity = 7;
        var deleted = new ArrayList<List<Object>>();
        var store = new Access() {
            @Override public <T> List<T> find(EntityMetadata<T> metadata, Query query) {
                assertEquals(List.of("player", "activity"), query.criteria().stream().map(Query.Criterion::property).toList());
                assertEquals(List.of(10001L, 7), query.criteria().stream().map(Query.Criterion::value).toList());
                return List.of(metadata.type().cast(row));
            }
            @Override public BatchResult applyBatch(List<WriteOperation<?>> operations) {
                for (var operation : operations) {
                    if (operation.type() == WriteType.DELETE_GROUP) {
                        assertEquals("10001:7", operation.groupKey());
                        deleted.add(operation.metadata().groupKeyValues(operation.groupKey()));
                    }
                }
                return BatchResult.success(operations.size());
            }
        };
        try (var data = new GameData(store)) {
            var repository = data.repository(Rows.class);
            var group = repository.getGroup("10001:7");
            assertSame(row, group.get(1L));
            assertEquals("10001:7", EntityMetadata.inspect(Row.class).groupKey(row));
            var next = new Row(); next.id = 2; next.player = 10001; next.activity = 7;
            repository.insert(next); assertSame(next, group.get(2L));
            repository.deleteGroup("10001:7"); assertTrue(group.isEmpty());
            data.flush();
            assertEquals(List.of(List.of(10001L, 7)), deleted);
        }
    }

    @Test void compositeScalarValuesRoundTripIncludingTrailingEmptyStrings() {
        var row = new Scalars(); row.kind = Kind.DAILY; row.owner = UUID.randomUUID();
        row.active = true; row.tag = ""; row.day = LocalDate.of(2026, 9, 19);
        var metadata = EntityMetadata.inspect(Scalars.class);
        assertEquals(List.of(row.kind, row.owner, true, row.day, ""), metadata.groupKeyValues(metadata.groupKey(row)));
        assertEquals("DAILY:" + row.owner + ":true:2026-09-19:", metadata.groupKey(row));
    }

    @Test void malformedCompositeKeysFailBeforeQuerying() {
        var metadata = EntityMetadata.inspect(Row.class);
        for (Object key : List.of(12L, "1", "1:2:3", "x:2", "1:2147483648")) {
            assertThrows(DataException.class, () -> metadata.groupKeyValues(key));
        }
        assertThrows(IllegalArgumentException.class, () -> GroupKeys.of());
        assertThrows(NullPointerException.class, () -> GroupKeys.of(1, null));
        assertThrows(IllegalArgumentException.class, () -> GroupKeys.of("a:b", "c"));
        assertThrows(IllegalArgumentException.class, () -> MapKeys.of("a", "b:c"));
    }
}
