package cn.managame.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.Metadata;
import cn.managame.runtime.annotation.RouteKey;
import cn.managame.runtime.executor.RouteExecutors;
import org.junit.jupiter.api.Test;

class RouteKeysTest {
    private static final int PLAYER = 1, GUILD = 2, ROOM = 3;
    private static final byte ROLE = 1;
    private static final GameContext ANYONE = GameContext.of(0, 0);

    record Donate(@RouteKey(GUILD) long guildId, int amount) {}
    record Chat(String text) {
        @RouteKey(ROOM) int room() { return 9; }
    }
    static class Base {
        @RouteKey({GUILD, ROOM}) long id = 5;
    }
    static class Derived extends Base {}
    record Boxed(@RouteKey(GUILD) Long guildId) {}

    @Test void annotatedFindsComponentsMethodsAndInheritedFields() {
        assertEquals(7, RouteKeys.annotated(GUILD).routeKey(ANYONE, new Donate(7, 100)));
        assertEquals(9, RouteKeys.annotated(ROOM).routeKey(ANYONE, new Chat("hi")));
        assertEquals(5, RouteKeys.annotated(GUILD).routeKey(ANYONE, new Derived()));
        assertEquals(5, RouteKeys.annotated(ROOM).routeKey(ANYONE, new Derived()), "one member may route several domains");
        assertEquals(8, RouteKeys.annotated(GUILD).routeKey(ANYONE, new Boxed(8L)));
        assertThrows(IllegalArgumentException.class, () -> RouteKeys.annotated(PLAYER).routeKey(ANYONE, new Donate(7, 100)));
    }

    @Test void theRuntimeRoutesByTheDomainsResolverActingForTheSource() {
        try (GameRuntime runtime = GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())
                .domain(GUILD, RouteExecutors.virtualThreads())
                .domain(ROOM, RouteExecutors.virtualThreads(), (source, message) -> 42).build()) {
            GameContext role = new GameContext(0, 0, ROLE, 1001, Metadata.empty());
            Donate donate = new Donate(7, 100);
            assertEquals(Route.of(PLAYER, 1001, ROLE, 1001), runtime.route(PLAYER, role, donate));
            assertEquals(Route.of(GUILD, 7, ROLE, 1001), runtime.route(GUILD, role, donate), "the guild acts for the role");
            assertEquals(Route.of(ROOM, 42, ROLE, 1001), runtime.route(ROOM, role, donate));
            assertThrows(IllegalArgumentException.class, () -> runtime.route(99, role, donate));
        }
    }
}
