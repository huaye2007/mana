package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.executor.RouteExecutors;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HandleInterceptorTest {
    private static final int PLAYER = 1;
    private static final byte ROLE = 1;
    private static final int NOT_LOGGED_IN = 9001;

    /** The application's own marker for handlers that need no login. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Anonymous {}

    record Buy(int item) {}
    record Hello() {}
    record Boom() {}
    record Later() {}
    record Note() {}

    private final GameRuntime runtime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId()).build();
    private final List<String> trace = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final GameContext role = GameContext.of(0, 0, ROLE, 1001), nobody = GameContext.of(0, 0);

    @Domain(PLAYER)
    class Shop {
        @HandleMethod String buy(Buy buy) {
            trace.add("buy");
            return "bought " + buy.item();
        }
        @Anonymous @HandleMethod String hello(Hello hello) {
            trace.add("hello");
            return "hi";
        }
        @HandleMethod String boom(Boom boom) { throw new IllegalStateException("boom"); }
        @HandleMethod String later(HandleContext context, Later later) {
            Thread.ofVirtual().start(() -> runtime.execute(context, again -> again.reply("later")));
            return null;
        }
        @HandleMethod void note(Note note) { trace.add("note"); }
    }

    /** Records what it sees, under its name. */
    class Recorder implements HandleInterceptor {
        private final String name;

        Recorder(String name) { this.name = name; }

        @Override public int before(HandleContext context, Method handler, Object message) {
            Context current = Context.current();
            trace.add(name + " before " + handler.getName() + " on " + current.domain() + ":" + current.routeKey());
            return SUCCESS;
        }

        @Override public void after(HandleContext context, Method handler, Object message, Object result, Throwable failure) {
            trace.add(name + " after " + result + (failure == null ? "" : " " + failure.getMessage()));
        }
    }

    class LoginCheck implements HandleInterceptor {
        @Override public int before(HandleContext context, Method handler, Object message) {
            return handler.isAnnotationPresent(Anonymous.class) || context.businessId() != 0 ? SUCCESS : NOT_LOGGED_IN;
        }
    }

    @AfterEach void stop() { runtime.close(); }

    private Dispatcher dispatcher(HandleInterceptor... interceptors) {
        return Dispatcher.builder(runtime).intercept(interceptors).register(new Shop()).build();
    }

    private String ask(Dispatcher dispatcher, Context source, Object message) throws InterruptedException {
        dispatcher.dispatch(source, message, result -> {
            trace.add("reply " + result);
            answers.add("ok " + result);
        }, errorCode -> answers.add("fail " + errorCode));
        return answers.poll(5, TimeUnit.SECONDS);
    }

    @Test void interceptorsWrapTheHandlerOnItsRouteBeforeTheAnswer() throws Exception {
        assertEquals("ok bought 5", ask(dispatcher(new Recorder("a"), new Recorder("b")), role, new Buy(5)));
        assertEquals(List.of("a before buy on " + PLAYER + ":1001", "b before buy on " + PLAYER + ":1001", "buy",
                "b after bought 5", "a after bought 5", "reply bought 5"), trace);
    }

    @Test void aRejectionFailsTheRequestAndNothingElseRuns() throws Exception {
        Dispatcher dispatcher = dispatcher(new Recorder("a"), new LoginCheck(), new Recorder("b"));
        assertEquals("fail " + NOT_LOGGED_IN, ask(dispatcher, nobody, new Buy(5)));
        assertEquals(List.of("a before buy on " + PLAYER + ":0"), trace, "no handler, later interceptor or after");

        trace.clear();
        dispatcher.dispatch(nobody, new Note()); // expects no answer: dropped
        assertEquals("ok hi", ask(dispatcher, nobody, new Hello()), "an @Anonymous handler needs no login");
        assertFalse(trace.contains("note"));
        assertEquals("hello", trace.get(trace.size() - 4));
    }

    @Test void afterSeesFailuresAndAnswersGivenLater() throws Exception {
        Dispatcher dispatcher = dispatcher(new Recorder("a"));
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, ask(dispatcher, role, new Boom()));
        assertEquals("a after null boom", trace.getLast());
        assertEquals("ok later", ask(dispatcher, role, new Later()));
        assertEquals(List.of("a after null", "reply later"), trace.subList(trace.size() - 2, trace.size()),
                "the handler returned without answering, and the answer came after");
    }

    @Test void aFailingInterceptorFailsTheRequestYetEveryAfterRuns() throws Exception {
        HandleInterceptor failingAfter = new HandleInterceptor() {
            @Override public void after(HandleContext context, Method handler, Object message, Object result, Throwable failure) {
                throw new IllegalStateException("after");
            }
        };
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, ask(dispatcher(new Recorder("a"), failingAfter), role, new Buy(5)));
        assertEquals(List.of("a before buy on " + PLAYER + ":1001", "buy", "a after bought 5"), trace, "no reply");

        trace.clear();
        HandleInterceptor failingBefore = new HandleInterceptor() {
            @Override public int before(HandleContext context, Method handler, Object message) { throw new IllegalStateException("before"); }
        };
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, ask(dispatcher(failingBefore), role, new Buy(5)));
        assertEquals(List.of(), trace);
    }
}
