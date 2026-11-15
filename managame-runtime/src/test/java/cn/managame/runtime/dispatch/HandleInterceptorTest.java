package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.Metadata;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.TaskInterceptor;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.executor.RouteExecutors;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HandleInterceptorTest {
    private static final int PLAYER = 1;
    private static final byte ROLE = 1, MARKER = 99;
    private static final int FEATURE_CLOSED = 10_001;   // the game's own code

    record Buy(int item) {}
    record Chat(String text) {}
    record Enter() {}
    record Boom() {}
    record Unknown() {}

    /** What an application marks its handlers with, to switch a feature off by its name. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Feature { String value(); }

    private final List<String> trace = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
    private final GameContext role = GameContext.of(0, 0, ROLE, 1001);
    /** What the request that enters does off its route, before it continues there. */
    private volatile Runnable beforeContinuing = () -> {};
    private GameRuntime runtime;
    private Dispatcher dispatcher;

    @Domain(PLAYER)
    @Feature("shop")
    class Shop {
        @HandleMethod HandleResult buy(Buy buy) {
            trace.add("buy");
            return HandleResult.ok("bought " + buy.item());
        }
        @HandleMethod void boom(Boom boom) { throw new IllegalStateException("boom"); }
        /** Answers from the task that continues the request, as after a call to another server. */
        @HandleMethod void enter(HandleContext context, Enter enter) {
            trace.add("enter");
            Thread.ofVirtual().start(() -> {
                beforeContinuing.run();
                runtime.execute(context, again -> {
                    trace.add("continued");
                    again.reply("entered");
                });
            });
        }
    }

    @Domain(PLAYER)
    @Feature("chat")
    class Chats {
        @HandleMethod HandleResult chat(Chat chat) { return HandleResult.ok("said " + chat.text()); }
    }

    /** Refuses the messages of a feature switched off, which it knows by the annotation on their handler's class. */
    static class FeatureSwitch implements HandleInterceptor {
        final Set<String> closed = ConcurrentHashMap.newKeySet();

        @Override public boolean before(HandleContext context, Method handler, Object message) {
            Feature feature = handler.getDeclaringClass().getAnnotation(Feature.class);
            if (feature == null || !closed.contains(feature.value())) return true;
            context.fail(FEATURE_CLOSED, feature.value());
            return false;
        }
    }

    /** Records what it is given, under its name. */
    class Recorder implements HandleInterceptor {
        private final String name;

        Recorder(String name) { this.name = name; }

        @Override public boolean before(HandleContext context, Method handler, Object message) {
            trace.add(name + " before " + handler.getDeclaringClass().getSimpleName() + "#" + handler.getName() + " " + message
                    + (Context.current() == context ? "" : " elsewhere"));
            return true;
        }

        @Override public void after(HandleContext context, Method handler, Object message, Throwable failure) {
            trace.add(name + " after" + (failure == null ? "" : " " + failure.getMessage()));
        }
    }

    /** The runtime's own interceptor, around every task; tasks that only mark a route's end are left out. */
    private final TaskInterceptor everyTask = new TaskInterceptor() {
        @Override public boolean before(Context context) {
            if (context.businessType() != MARKER) trace.add("task before");
            return true;
        }

        @Override public void after(Context context, Throwable failure) {
            if (context.businessType() != MARKER) trace.add("task after");
        }
    };

    private void start(HandleInterceptor... interceptors) {
        runtime = GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).intercept(List.of(everyTask))
                .errorHandler((context, cause) -> errors.add(cause)).build();
        dispatcher = Dispatcher.builder(runtime).intercept(List.of(interceptors)).register(List.of(new Shop(), new Chats())).build();
    }

    @AfterEach void stop() { runtime.close(); }

    /** Dispatches and waits for the answer, then for the tasks given to the player's route so far to end, afters included. */
    private String ask(Object message) throws InterruptedException {
        dispatcher.dispatch(role, message, result -> answers.add(result.code() == SUCCESS ? "ok " + result.data()
                : "fail " + result.code() + (result.args().length == 0 ? "" : " " + String.join(",", result.args()))));
        String answer = answers.poll(5, TimeUnit.SECONDS);
        CountDownLatch ended = new CountDownLatch(1);
        runtime.execute(new GameContext(PLAYER, 1001, MARKER, 0, Metadata.empty()), _ -> ended.countDown());
        assertTrue(ended.await(5, TimeUnit.SECONDS));
        return answer;
    }

    @Test void interceptorsRunAroundTheHandlerWithItsMethodAndItsMessage() throws Exception {
        start(new Recorder("a"), new Recorder("b"));
        assertEquals("ok bought 5", ask(new Buy(5)));
        assertEquals(List.of("task before", "a before Shop#buy Buy[item=5]", "b before Shop#buy Buy[item=5]", "buy", "b after", "a after", "task after"),
                trace, "inside the runtime's interceptors, befores in order and afters in reverse, on the handler's route");
        assertNull(errors.poll());
    }

    @Test void aFeatureSwitchedOffIsRefusedBeforeItsHandlerRuns() throws Exception {
        FeatureSwitch features = new FeatureSwitch();
        start(features, new Recorder("a"));
        features.closed.add("shop");
        assertEquals("fail " + FEATURE_CLOSED + " shop", ask(new Buy(5)), "answered by the interceptor");
        assertEquals(List.of("task before", "task after"), trace, "neither the handler nor the interceptor after it ran");
        assertEquals("ok said hi", ask(new Chat("hi")), "another feature is as it was");
        assertNull(errors.poll(), "a refusal is no error");

        features.closed.remove("shop");
        assertEquals("ok bought 5", ask(new Buy(5)), "and switched on again while the server runs");
    }

    @Test void itRunsOnceForAMessageAndNotForTheTaskThatContinuesIt() throws Exception {
        FeatureSwitch features = new FeatureSwitch();
        start(features, new Recorder("a"));
        beforeContinuing = () -> features.closed.add("shop");   // switched off while the request is on its way
        assertEquals("ok entered", ask(new Enter()), "what was let in goes on to its end");
        assertEquals(List.of("task before", "a before Shop#enter Enter[]", "enter", "a after", "task after", "task before", "continued", "task after"),
                trace, "the runtime's interceptor sees both tasks, the Dispatcher's the handler only");
        assertEquals("fail " + FEATURE_CLOSED + " shop", ask(new Enter()), "the next one is refused");
    }

    @Test void aBeforeThatRefusesWithoutAnsweringFailsTheRequest() throws Exception {
        HandleInterceptor silent = new HandleInterceptor() {
            @Override public boolean before(HandleContext context, Method handler, Object message) { return false; }

            @Override public void after(HandleContext context, Method handler, Object message, Throwable failure) { trace.add("never"); }
        };
        start(new Recorder("a"), silent, new Recorder("b"));
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, ask(new Buy(5)), "a request is always answered");
        assertEquals(List.of("task before", "a before Shop#buy Buy[item=5]", "a after", "task after"), trace,
                "only the interceptors whose before returned true have their after run, with no failure");
        assertNull(errors.poll());
    }

    @Test void whatTheHandlerOrAnInterceptorThrowsIsHandledAsTheHandlersFailure() throws Exception {
        HandleInterceptor[] broken = {new HandleInterceptor() {}};
        start(new Recorder("a"), new HandleInterceptor() {
            @Override public boolean before(HandleContext context, Method handler, Object message) throws Exception { return broken[0].before(context, handler, message); }

            @Override public void after(HandleContext context, Method handler, Object message, Throwable failure) throws Exception { broken[0].after(context, handler, message, failure); }
        });
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, ask(new Boom()));
        assertEquals("boom", errors.poll(5, TimeUnit.SECONDS).getMessage());
        assertEquals(List.of("task before", "a before Shop#boom Boom[]", "a after boom", "task after"), trace, "an after is told what the handler threw");

        trace.clear();
        broken[0] = new HandleInterceptor() {
            @Override public boolean before(HandleContext context, Method handler, Object message) { throw new IllegalStateException("before broke"); }
        };
        assertEquals("fail " + RUNTIME_EXECUTION_ERROR, ask(new Buy(5)));
        assertEquals("before broke", errors.poll(5, TimeUnit.SECONDS).getMessage());
        assertEquals(List.of("task before", "a before Shop#buy Buy[item=5]", "a after before broke", "task after"), trace, "the handler did not run");

        trace.clear();
        broken[0] = new HandleInterceptor() {
            @Override public void after(HandleContext context, Method handler, Object message, Throwable failure) { throw new IllegalStateException("after broke"); }
        };
        assertEquals("ok bought 5", ask(new Buy(5)), "the answer the handler returned was on its way already");
        assertEquals("after broke", errors.poll(5, TimeUnit.SECONDS).getMessage());
        assertEquals(List.of("task before", "a before Shop#buy Buy[item=5]", "buy", "a after", "task after"), trace, "the other afters run all the same");
    }

    @Test void aMessageThatReachesNoHandlerReachesNoInterceptor() throws Exception {
        start(new Recorder("a"));
        assertEquals("fail " + HANDLER_NOT_FOUND, ask(new Unknown()));
        assertEquals(List.of(), trace);
    }
}
