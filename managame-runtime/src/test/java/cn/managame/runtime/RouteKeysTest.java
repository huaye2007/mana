package cn.managame.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.Metadata;
import cn.managame.runtime.annotation.AnnotatedMethod;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.executor.RouteExecutors;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteKeysTest {
    private static final int PLAYER = 1, GUILD = 2, ROOM = 3;
    private static final byte ROLE = 1;

    record Donate(long guildId, int amount) {}
    record Chat(String text) {
        int roomId() { return 9; }
    }
    /** As protobuf generates it: a private field with a trailing underscore and a getter. */
    static final class ApplyReq {
        private final long guildId_ = 6;
        public long getGuildId() { return guildId_; }
    }
    static class Base {
        long guildId = 5;
    }
    static class Derived extends Base {}
    record Boxed(Long guildId) {}
    record Named(String guildId) {}
    /** A method of the exact name wins over the getter. */
    static final class Both {
        long guildId() { return 1; }
        long getGuildId() { return 2; }
    }

    private final GameRuntime runtime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads())
            .domain(GUILD, RouteExecutors.virtualThreads())
            .domain(ROOM, RouteExecutors.virtualThreads(), (source, message) -> 42).build();
    private final GameContext role = new GameContext(0, 0, ROLE, 1001, Metadata.empty());

    @AfterEach void stop() { runtime.close(); }

    @Test void theRuntimeRoutesByTheDomainsResolverActingForTheSource() {
        Donate donate = new Donate(7, 100);
        assertEquals(Route.of(PLAYER, 1001, ROLE, 1001), runtime.route(PLAYER, role, donate), "by the acting role, by default");
        assertEquals(Route.of(ROOM, 42, ROLE, 1001), runtime.route(ROOM, role, donate));
        assertThrows(IllegalArgumentException.class, () -> runtime.route(99, role, donate));
    }

    @Domain(GUILD)
    static class Guild {
        @HandleMethod(routeKey = "guildId") void donate(Donate donate) {}
        @HandleMethod(routeKey = "guildId") void apply(ApplyReq apply) {}
        @HandleMethod(routeKey = "getGuildId") void applyByGetter(ApplyReq apply) {}
        @HandleMethod(routeKey = "guildId") void derived(Derived derived) {}
        @HandleMethod(routeKey = "guildId") void boxed(Boxed boxed) {}
        @HandleMethod(routeKey = "guildId") void both(Both both) {}
        @HandleMethod void mine(Chat chat) {}                                   // no routeKey: the domain's resolver
        @Domain(ROOM) @HandleMethod(routeKey = "roomId") void chat(Chat chat) {} // an int, widened
    }

    @Test void aHandlerRoutesByTheMemberItsAnnotationNamesActingForTheSource() {
        AnnotatedMethod donate = handler("donate"), apply = handler("apply"), applyByGetter = handler("applyByGetter");
        assertEquals(Route.of(GUILD, 7, ROLE, 1001), donate.route(runtime, role, new Donate(7, 100)), "a record component");
        assertEquals(6, apply.route(runtime, role, new ApplyReq()).routeKey(), "a protobuf getter, by the field's name");
        assertEquals(6, applyByGetter.route(runtime, role, new ApplyReq()).routeKey(), "or by the getter's own name");
        assertEquals(5, handler("derived").route(runtime, role, new Derived()).routeKey(), "an inherited field");
        assertEquals(8, handler("boxed").route(runtime, role, new Boxed(8L)).routeKey(), "a box");
        assertThrows(NullPointerException.class, () -> handler("boxed").route(runtime, role, new Boxed(null)), "a null box cannot route");
        assertEquals(1, handler("both").route(runtime, role, new Both()).routeKey());
        assertEquals(Route.of(GUILD, 1001, ROLE, 1001), handler("mine").route(runtime, role, new Chat("hi")));
        assertEquals(Route.of(ROOM, 9, ROLE, 1001), handler("chat").route(runtime, role, new Chat("hi")));
    }

    @Test void aMemberTheMessageLacksOrThatIsNotIntegralFailsAtRegistration() {
        @Domain(GUILD) class Misspelt { @HandleMethod(routeKey = "guild") void donate(Donate donate) {} }
        @Domain(GUILD) class NotIntegral { @HandleMethod(routeKey = "guildId") void named(Named named) {} }
        assertThrows(IllegalArgumentException.class, () -> AnnotatedMethod.scan(new Misspelt(), HandleMethod.class, HandleMethod::routeKey));
        assertThrows(IllegalArgumentException.class, () -> AnnotatedMethod.scan(new NotIntegral(), HandleMethod.class, HandleMethod::routeKey));
    }

    private static AnnotatedMethod handler(String name) {
        List<AnnotatedMethod> methods = AnnotatedMethod.scan(new Guild(), HandleMethod.class, HandleMethod::routeKey);
        return methods.stream().filter(method -> method.method().getName().equals(name)).findFirst().orElseThrow();
    }
}
