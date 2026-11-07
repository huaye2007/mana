package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.Metadata;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.http.HttpRequestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DispatcherTest {
    private static final int PLAYER = 1, GUILD = 2;
    private static final byte ROLE = 1;
    private static final int NOT_ENOUGH = 10_001;   // the game's own code

    record Buy(int item) {}
    record Donate(long guildId) {}
    record Unroutable(Long guildId) {}
    record Boom() {}
    record Later(String text) {}
    record Note(String text) {}
    record Unknown() {}
    record Trade(long with, int item) {}
    record Sell(int item) {}
    record Refuse() {}
    record Nothing() {}
    record Twice() {}
    record LaterAfterNull(String text) {}

    private final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
    private final GameRuntime runtime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())
            .domain(GUILD, RouteExecutors.virtualThreads())   // its handlers route by their message's guildId
            .errorHandler((context, cause) -> errors.add(cause)).build();
    private final List<String> notes = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final GameContext role = new GameContext(0, 0, ROLE, 1001, Metadata.empty());
    private final Map<Long, Set<Integer>> bags = Map.of(1001L, ConcurrentHashMap.newKeySet(), 2002L, ConcurrentHashMap.newKeySet());
    private final List<Long> routes = new CopyOnWriteArrayList<>();

    @Domain(PLAYER)
    class Shop {
        @HandleMethod HandleResult buy(HandleContext context, Buy buy) {
            return HandleResult.ok("bought " + buy.item() + " by " + context.businessId() + " on " + context.domain() + ":" + context.routeKey());
        }
        @HandleMethod void note(Note note) { notes.add(note.text()); }
        @HandleMethod void boom(Boom boom) { throw new IllegalStateException("boom"); }
        @HandleMethod HandleResult sell(HandleContext context, Sell sell) { return sellItem(context.businessId(), sell.item()); }
        @HandleMethod HandleResult refuse(Refuse refuse) { return HandleResult.fail(NOT_ENOUGH, "a", "b"); }
        @HandleMethod HandleResult nothing(Nothing nothing) { return HandleResult.ok(null); }
        @HandleMethod HandleResult twice(HandleContext context, Twice twice) {
            context.reply("first");
            return HandleResult.ok("second");
        }
        @HandleMethod void later(HandleContext context, Later later) {
            Thread.ofVirtual().start(() -> runtime.execute(context, again -> again.reply("later " + later.text())));
        }
        @HandleMethod HandleResult laterAfterNull(HandleContext context, LaterAfterNull later) {
            Thread.ofVirtual().start(() -> runtime.execute(context, again -> again.reply("later " + later.text())));
            return null;
        }
        @HandleMethod void trade(HandleContext request, Trade trade) {
            runtime.execute(GameContext.of(PLAYER, trade.with()), _ -> {     // on the other player's route
                routes.add(Context.current().routeKey());
                boolean taken = bags.get(trade.with()).remove(trade.item());
                runtime.execute(request, _ -> {                               // back on this player's route
                    routes.add(Context.current().routeKey());
                    if (!taken) { request.fail(NOT_ENOUGH, String.valueOf(trade.item())); return; }
                    bags.get(request.businessId()).add(trade.item());
                    request.reply("traded");
                });
            });
        }
    }

    @Domain(GUILD)
    class Guild {
        @HandleMethod(routeKey = "guildId") HandleResult donate(HandleContext context, Donate donate) {
            return HandleResult.ok("donated to " + context.routeKey() + " by " + context.businessId());
        }
        @HandleMethod(routeKey = "guildId") void unroutable(Unroutable message) {}
    }

    private Dispatcher dispatcher() { return Dispatcher.builder(runtime).register(List.of(new Shop(), new Guild())).build(); }

    private void dispatch(Dispatcher dispatcher, Object message) {
        dispatcher.dispatch(role, message, result -> answers.add(result.code() == SUCCESS ? "ok:" + result.data()
                : "fail:" + result.code() + (result.args().length == 0 ? "" : ":" + String.join(",", result.args()))));
    }

    private String answer() throws InterruptedException { return answers.poll(5, TimeUnit.SECONDS); }

    @AfterEach void stop() { runtime.close(); }

    @Test void eachMessageRunsInItsHandlersDomainActingForTheSource() throws Exception {
        Dispatcher dispatcher = dispatcher();
        dispatch(dispatcher, new Buy(5));
        assertEquals("ok:bought 5 by 1001 on " + PLAYER + ":1001", answer(), "the player domain keys by the acting role");
        dispatch(dispatcher, new Donate(7));
        assertEquals("ok:donated to 7 by 1001", answer(), "the guild domain keys by the message");
    }

    @Test void aTradeTakesOnTheOtherPlayersRouteAndGivesBackOnItsOwn() throws Exception {
        bags.get(2002L).add(9);
        Dispatcher dispatcher = dispatcher();
        dispatch(dispatcher, new Trade(2002, 9));
        assertEquals("ok:traded", answer());
        assertEquals(List.of(2002L, 1001L), routes, "taken on the other player's route, given on the trader's");
        assertEquals(Set.of(9), bags.get(1001L));
        dispatch(dispatcher, new Trade(2002, 9));
        assertEquals("fail:" + NOT_ENOUGH + ":9", answer(), "the other player has none left; the item goes with the code");
    }

    /** The game's own logic, apart from its handler: returns what it did, or why it could not. */
    private HandleResult sellItem(long player, int item) {
        if (!bags.get(player).remove(item)) return HandleResult.fail(NOT_ENOUGH, String.valueOf(item));
        return HandleResult.ok("sold " + item);
    }

    @Test void aHandlerAnswersWithWhatItsLogicReturns() throws Exception {
        bags.get(1001L).add(3);
        Dispatcher dispatcher = dispatcher();
        dispatch(dispatcher, new Sell(3));
        assertEquals("ok:sold 3", answer());
        dispatch(dispatcher, new Sell(3));
        assertEquals("fail:" + NOT_ENOUGH + ":3", answer(), "the code and args it failed with");
        dispatcher.dispatch(role, new Sell(3));   // one-way, as a notify: no one to answer
        dispatch(dispatcher, new Buy(1));         // after it on the same route
        assertEquals("ok:bought 1 by 1001 on " + PLAYER + ":1001", answer());
        assertTrue(errors.isEmpty(), "a failure is an answer, never an error, even with no one to answer");
    }

    @Test void whatAHandlerReturnsIsItsAnswer() throws Exception {
        Dispatcher dispatcher = dispatcher();
        dispatch(dispatcher, new Refuse());
        assertEquals("fail:" + NOT_ENOUGH + ":a,b", answer(), "a failure returned, with its args");
        dispatch(dispatcher, new Nothing());
        assertEquals("ok:null", answer(), "a success with nothing to send back");
        dispatch(dispatcher, new Twice());
        assertEquals("ok:first", answer(), "only the first answer counts");
        dispatch(dispatcher, new LaterAfterNull("x"));
        assertEquals("ok:later x", answer(), "null means the answer comes later through the context");
        assertNull(answers.poll(100, TimeUnit.MILLISECONDS), "each message was answered once");
    }

    @Test void aHandlerWhoseDomainCannotRouteItsMessageFailsAtRegistration() {
        @Domain(GUILD) class Misrouted { @HandleMethod(routeKey = "guildId") void rest(Buy buy) {} }   // Buy has no guildId
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(List.of(new Misrouted())));
    }

    @Test void failuresAreAnsweredWithErrorCodes() throws Exception {
        Dispatcher dispatcher = dispatcher();
        dispatch(dispatcher, new Unknown());
        assertEquals("fail:" + HANDLER_NOT_FOUND, answer(), "the framework's own failures carry no args");
        dispatch(dispatcher, new Unroutable(null));
        assertEquals("fail:" + INVALID_ROUTE_KEY, answer());
        dispatch(dispatcher, new Boom());
        assertEquals("fail:" + RUNTIME_EXECUTION_ERROR, answer());
        runtime.shutdown();
        dispatch(dispatcher, new Buy(1));
        assertEquals("fail:" + RUNTIME_CLOSED, answer());
    }

    @Test void anAnswerMayComeLaterAndOneWayMessagesExpectNone() throws Exception {
        Dispatcher dispatcher = dispatcher();
        dispatch(dispatcher, new Later("x"));
        assertEquals("ok:later x", answer());
        dispatcher.dispatch(role, new Note("hi"));
        dispatch(dispatcher, new Buy(1)); // runs after the note on the same route
        answer();
        assertEquals(List.of("hi"), notes);
        assertNull(answers.poll(100, TimeUnit.MILLISECONDS));
    }

    @Domain(PLAYER) static class Duplicate { @HandleMethod void again(Buy buy) {} }
    @Domain(PLAYER) static class Returning { @HandleMethod String buy(Buy buy) { return "bought"; } }
    @Domain(PLAYER) static class WrongContext { @HandleMethod void buy(HttpRequestContext context, Buy buy) {} }
    static class NoDomain { @HandleMethod void buy(Buy buy) {} }
    @Domain(PLAYER) static class TooMany { @HandleMethod void buy(HandleContext context, Buy buy, int extra) {} }

    @Test void registrationRejectsWhatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(List.of(new Shop(), new Duplicate())));
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(List.of(new Returning())),
                "an answer is a HandleResult, never another type");
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(List.of(new WrongContext())));
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(List.of(new NoDomain())));
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(List.of(new TooMany())));
    }
}
