package cn.managame.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.Metadata;
import cn.managame.runtime.executor.RouteExecutors;
import org.junit.jupiter.api.Test;

class RouteKeysTest {
    private static final int PLAYER = 1, GUILD = 2, ROOM = 3;
    private static final byte ROLE = 1;
    private static final GameContext ANYONE = GameContext.of(0, 0);

    record Donate(long guildId, int amount) {}
    record Chat(String text) {
        int roomId() { return 9; }
    }
    static class Base {
        long guildId = 5;
    }
    static class Derived extends Base {}
    record Boxed(Long guildId) {}
    record Named(String guildId) {}

    @Test void fieldReadsComponentsMethodsInheritedFieldsAndBoxes() {
        assertEquals(7, RouteKeys.field("guildId").routeKey(ANYONE, new Donate(7, 100)));
        assertEquals(9, RouteKeys.field("roomId").routeKey(ANYONE, new Chat("hi")));
        assertEquals(5, RouteKeys.field("guildId").routeKey(ANYONE, new Derived()));
        assertEquals(8, RouteKeys.field("guildId").routeKey(ANYONE, new Boxed(8L)));
    }

    @Test void fieldRefusesAClassItCannotRouteWhenChecked() {
        RouteKeys.field("guildId").check(Donate.class);
        assertThrows(IllegalArgumentException.class, () -> RouteKeys.field("roomId").check(Donate.class), "no such member");
        assertThrows(IllegalArgumentException.class, () -> RouteKeys.field("guildId").check(Named.class), "not integral");
    }

    @Test void theRuntimeRoutesByTheDomainsResolverActingForTheSource() {
        try (GameRuntime runtime = GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads())
                .domain(GUILD, RouteExecutors.virtualThreads(), RouteKeys.field("guildId"))
                .domain(ROOM, RouteExecutors.virtualThreads(), (source, message) -> 42).build()) {
            GameContext role = new GameContext(0, 0, ROLE, 1001, Metadata.empty());
            Donate donate = new Donate(7, 100);
            assertEquals(Route.of(PLAYER, 1001, ROLE, 1001), runtime.route(PLAYER, role, donate), "by the acting role, by default");
            assertEquals(Route.of(GUILD, 7, ROLE, 1001), runtime.route(GUILD, role, donate), "the guild acts for the role");
            assertEquals(Route.of(ROOM, 42, ROLE, 1001), runtime.route(ROOM, role, donate));
            assertThrows(IllegalArgumentException.class, () -> runtime.route(99, role, donate));
            assertThrows(IllegalArgumentException.class, () -> runtime.checkRoute(GUILD, Chat.class));
            assertThrows(IllegalArgumentException.class, () -> runtime.checkRoute(99, Donate.class));
        }
    }
}
