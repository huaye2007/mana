package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.spi.DelegatingEntityMapper;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A server that has just opened: the roles created in the last minutes have nothing in the database yet, so the
 * application puts a mapper of its own around the real one, which answers for them without asking it.
 */
class DelegatingEntityMapperTest {
    static class Role {
        @Id long roleId;
        String name;

        Role() {}

        Role(long roleId, String name) {
            this.roleId = roleId;
            this.name = name;
        }
    }

    static class Equip {
        @Id long uid;
        @GroupKey long roleId;
        @MapKey int slot;

        Equip() {}

        Equip(long uid, long roleId, int slot) {
            this.uid = uid;
            this.roleId = roleId;
            this.slot = slot;
        }
    }

    static final class Roles extends SingleRepository<Long, Role> {}

    static final class Equips extends GroupRepository<Integer, Equip> {}

    /** By the application's rule that a role's id is always a field named roleId. */
    static final class NewRoleMapper extends DelegatingEntityMapper {
        /** The roles created a moment ago; the application takes one out some minutes later, or when it evicts its data. */
        private final Set<Long> newRoles;
        /** The types kept by role, found once at build: -1 for an @Id named roleId, or else its place among the @GroupKey fields. */
        private final Map<EntityType, Integer> roleKey = new ConcurrentHashMap<>();

        NewRoleMapper(EntityMapper mapper, Set<Long> newRoles) {
            super(mapper);
            this.newRoles = newRoles;
        }

        @Override public void prepare(EntityType type) {
            super.prepare(type);
            if (type.groupKeys().isEmpty()) {
                if (type.id().name().equals("roleId")) roleKey.put(type, -1);
                return;
            }
            for (int i = 0; i < type.groupKeys().size(); i++)
                if (type.groupKeys().get(i).name().equals("roleId")) roleKey.put(type, i);
        }

        @Override public Object load(EntityType type, Object id) throws Exception {
            return roleKey.containsKey(type) && newRoles.contains(id) ? null : super.load(type, id);
        }

        @Override public List<Object> loadAll(EntityType type, Collection<?> ids) throws Exception {
            if (!roleKey.containsKey(type)) return super.loadAll(type, ids);
            List<Object> stored = new ArrayList<>(ids);
            stored.removeIf(newRoles::contains);
            return stored.isEmpty() ? List.of() : super.loadAll(type, stored);
        }

        @Override public List<Object> loadGroup(EntityType type, GroupKeys key) throws Exception {
            Integer place = roleKey.get(type);
            return place != null && newRoles.contains(key.valueAt(place)) ? List.of() : super.loadGroup(type, key);
        }
    }

    private final MemoryMapper database = new MemoryMapper();
    private final Set<Long> newRoles = ConcurrentHashMap.newKeySet();
    private final GameData data = GameData.builder().errorHandler(new ReportedFailures())
            .repositories(new NewRoleMapper(database, newRoles), List.of(Roles.class, Equips.class, Players.class))
            .flushInterval(Duration.ofMinutes(1)).cacheExpire(Duration.ofMinutes(2)).build();
    private final Roles roles = data.repository(Roles.class);
    private final Equips equips = data.repository(Equips.class);

    @AfterEach
    void close() { data.close(); }

    @Test
    void readsNothingForANewRole() {
        newRoles.add(1L);
        assertNull(roles.get(1L));
        Role created = roles.getOrCreate(1L, id -> new Role(id, "ann"));
        assertTrue(equips.getGroup(GroupKeys.of(1L)).isEmpty());
        equips.insert(new Equip(10, 1, 0));
        assertEquals(0, database.loads.get(), "the database was never asked");

        assertSame(created, roles.get(1L));
        assertEquals(1, equips.getGroup(GroupKeys.of(1L)).size());
        data.flush();
        assertEquals(List.of("ann"), database.rows(Role.class).stream().map(row -> row.get("name")).toList());
        assertEquals(1, database.rows(Equip.class).size());
    }

    @Test
    void readsTheDatabaseForOtherRolesAndForTypesNotKeptByRole() {
        database.put(Role.class, new Role(2, "bob"));
        database.put(Equip.class, new Equip(20, 2, 0));
        database.put(Player.class, new Player(1, "a player whose id happens to be a new role's"));
        newRoles.add(1L);
        assertEquals("bob", roles.get(2L).name);
        assertEquals(1, equips.getGroup(GroupKeys.of(2L)).size());
        assertEquals(1L, data.repository(Players.class).get(1L).id, "its @Id is not named roleId");
        assertEquals(3, database.loads.get());
    }

    @Test
    void readsTheDatabaseAgainOnceTheRoleIsNoLongerNew() {
        newRoles.add(1L);
        roles.getOrCreate(1L, id -> new Role(id, "ann"));
        equips.insert(new Equip(10, 1, 0));
        data.flush();
        newRoles.remove(1L);   // as the application does before it evicts the role's data
        roles.evict(1L);
        equips.evict(GroupKeys.of(1L));
        assertEquals("ann", roles.get(1L).name);
        assertEquals(1, equips.getGroup(GroupKeys.of(1L)).size());
        assertEquals(2, database.loads.get());
    }

    @Test
    void getAllReadsOnlyTheIdsThatMayBeStored() {
        database.put(Role.class, new Role(2, "bob"));
        newRoles.add(1L);
        Map<Long, Role> found = roles.getAll(List.of(1L, 2L, 3L));
        assertEquals(Set.of(2L), found.keySet());
        assertEquals(Set.of(2L, 3L), Set.copyOf(database.lastBulkIds), "the new role's id is not asked for");
        assertNull(roles.get(1L));
        assertEquals(0, database.loads.get(), "remembered as missing, as an id the database does not have is");
    }

    @Test
    void keepsTheUnwrittenChangesOfANewRoleEvictedTooSoon() {
        newRoles.add(1L);
        Role created = roles.getOrCreate(1L, id -> new Role(id, "ann"));
        Equip equip = new Equip(10, 1, 0);
        equips.insert(equip);
        roles.evict(1L);
        equips.evict(GroupKeys.of(1L));   // still new, and nothing written yet: what was changed is not lost
        assertSame(created, roles.get(1L));
        assertSame(equip, equips.get(GroupKeys.of(1L), 0));
        assertEquals(0, database.loads.get());
    }

    @Test
    void handsOnWhatItDoesNotOverride() {
        MemoryMapper stored = new MemoryMapper() {
            @Override public boolean isRetryable(Throwable failure) { return failure instanceof UnsupportedOperationException; }
        };
        NewRoleMapper mapper = new NewRoleMapper(stored, Set.of());
        assertTrue(mapper.isRetryable(new UnsupportedOperationException()), "the mapper's own rule, not the default");
        assertFalse(mapper.isRetryable(new java.net.ConnectException()));

        try (GameData first = GameData.builder().errorHandler(new ReportedFailures()).repositories(mapper, List.of(Roles.class))
                .flushInterval(Duration.ofMinutes(1)).build()) {
            first.repository(Roles.class).insert(new Role(1, "ann"));
        }
        try (GameData preloaded = GameData.builder().errorHandler(new ReportedFailures()).preloadedRepositories(mapper, List.of(Roles.class)).build()) {
            assertEquals(1, preloaded.repository(Roles.class).all().size(), "written and read as a whole table through it");
        }
    }
}
