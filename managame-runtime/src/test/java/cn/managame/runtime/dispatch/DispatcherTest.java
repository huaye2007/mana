package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.Metadata;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.annotation.RouteKey;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.http.HttpRequestContext;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DispatcherTest {
    private static final int PLAYER = 1, GUILD = 2;
    private static final byte ROLE = 1;

    record Buy(int item) {}
    record Donate(@RouteKey(GUILD) long guildId) {}
    record Unroutable() {}
    record Boom() {}
    record Later(String text) {}
    record Note(String text) {}
    record Unknown() {}

    private final GameRuntime runtime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())
            .domain(GUILD, RouteExecutors.virtualThreads()).build();
    private final List<String> notes = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final GameContext role = new GameContext(0, 0, ROLE, 1001, Metadata.empty());

    @Domain(PLAYER)
    class Shop {
        @HandleMethod String buy(Buy buy) {
            Context context = Context.current();
            return "bought " + buy.item() + " by " + context.businessId() + " on " + context.domain() + ":" + context.routeKey();
        }
        @HandleMethod void note(Note note) { notes.add(note.text()); }
        @HandleMethod String boom(Boom boom) { throw new IllegalStateException("boom"); }
        @HandleMethod String later(HandleContext context, Later later) {
            Thread.ofVirtual().start(() -> runtime.execute(context, again -> again.reply("later " + later.text())));
            return null;
        }
    }

    @Domain(GUILD)
    class Guild {
        @HandleMethod String donate(Donate donate) {
            return "donated to " + Context.current().routeKey() + " by " + Context.current().businessId();
        }
        @HandleMethod String unroutable(Unroutable message) { return "never"; }
    }

    private Dispatcher dispatcher() { return Dispatcher.builder(runtime).register(new Shop(), new Guild()).build(); }

    private void dispatch(Dispatcher dispatcher, Object message) {
        dispatcher.dispatch(role, message, result -> answers.add("ok:" + result), code -> answers.add("fail:" + code));
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

    @Test void failuresAreAnsweredWithErrorCodes() throws Exception {
        Dispatcher dispatcher = dispatcher();
        dispatch(dispatcher, new Unknown());
        assertEquals("fail:" + HANDLER_NOT_FOUND, answer());
        dispatch(dispatcher, new Unroutable());
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

    @Domain(PLAYER) static class Duplicate { @HandleMethod String again(Buy buy) { return "again"; } }
    @Domain(PLAYER) static class WrongContext { @HandleMethod String buy(HttpRequestContext context, Buy buy) { return ""; } }
    static class NoDomain { @HandleMethod String buy(Buy buy) { return ""; } }
    @Domain(PLAYER) static class TooMany { @HandleMethod String buy(HandleContext context, Buy buy, int extra) { return ""; } }

    @Test void registrationRejectsWhatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(new Shop(), new Duplicate()));
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(new WrongContext()));
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(new NoDomain()));
        assertThrows(IllegalArgumentException.class, () -> Dispatcher.builder(runtime).register(new TooMany()));
        assertThrows(IllegalArgumentException.class, () -> dispatcher().dispatch(role, new Buy(1), result -> {}, null));
    }
}
