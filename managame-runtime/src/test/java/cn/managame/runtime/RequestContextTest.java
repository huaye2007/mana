package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.Metadata;
import cn.managame.runtime.executor.RouteExecutors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestContextTest {
    private static final int PLAYER = 1;
    private static final byte PLAYER_ID = 1;

    /** Stands in for a source's implementation: keeps the first answer. */
    private record TestRequest(int domain, long routingKey, byte businessType, long businessId, Metadata metadata,
                               AtomicReference<Object> answer, CountDownLatch answered) implements RequestContext {
        TestRequest(long playerId) {
            this(PLAYER, playerId, PLAYER_ID, playerId, Metadata.empty(), new AtomicReference<>(), new CountDownLatch(1));
        }

        @Override public boolean reply(Object result) { return answer(result); }
        @Override public boolean fail(int errorCode) { return answer("fail:" + errorCode); }

        private boolean answer(Object value) {
            if (!answer.compareAndSet(null, value)) return false;
            answered.countDown();
            return true;
        }

        Object awaitAnswer() throws InterruptedException {
            assertTrue(answered.await(5, TimeUnit.SECONDS), "the request was answered");
            return answer.get();
        }
    }

    private static GameRuntime runtime(BlockingQueue<Throwable> errors) {
        return GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads())
                .errorHandler((context, cause) -> errors.add(cause)).build();
    }

    @Test void businessLogicAnswersWithoutKnowingTheSource() throws Exception {
        TestRequest request = new TestRequest(1001);
        GameTask<RequestContext> buy = context -> context.reply("bought by " + context.businessId());
        try (GameRuntime runtime = runtime(new LinkedBlockingQueue<>())) {
            runtime.execute(request, buy);
            assertEquals("bought by 1001", request.awaitAnswer());
        }
    }

    @Test void aTaskThatThrowsFailsItsRequest() throws Exception {
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        TestRequest request = new TestRequest(1001);
        try (GameRuntime runtime = runtime(errors)) {
            runtime.execute(request, context -> { throw new IllegalStateException("boom"); });
            assertEquals("fail:" + RUNTIME_EXECUTION_ERROR, request.awaitAnswer());
            assertTrue(errors.poll(5, TimeUnit.SECONDS) instanceof IllegalStateException, "the error is still reported");
        }
    }

    @Test void anAnswerGivenBeforeTheTaskThrowsStands() throws Exception {
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        TestRequest request = new TestRequest(1001);
        try (GameRuntime runtime = runtime(errors)) {
            runtime.execute(request, (RequestContext context) -> {
                context.reply("done");
                throw new IllegalStateException("after answering");
            });
            assertTrue(errors.poll(5, TimeUnit.SECONDS) instanceof IllegalStateException);
            assertEquals("done", request.answer().get());
        }
    }

    @Test void aCallbackResumesOnTheOriginalContextAndAnswersLater() throws Exception {
        // As when a call to another server returns: the callback runs on the caller's route with the caller's
        // context, after the first task has returned, and can still answer the player.
        TestRequest request = new TestRequest(1001);
        try (GameRuntime runtime = runtime(new LinkedBlockingQueue<>())) {
            runtime.execute(request, context -> Thread.ofVirtual().start(() ->
                    runtime.execute(context, (RequestContext resumed) -> resumed.reply("rank " + resumed.routingKey()))));
            assertEquals("rank 1001", request.awaitAnswer());
        }
    }

    @Test void aRefusedRequestIsLeftToItsSubmitter() {
        TestRequest request = new TestRequest(1001);
        GameRuntime runtime = runtime(new LinkedBlockingQueue<>());
        runtime.close();
        GameRuntimeException refused = assertThrows(GameRuntimeException.class,
                () -> runtime.execute(request, context -> context.reply("never")));
        assertEquals(RUNTIME_CLOSED, refused.errorCode());
        assertNull(request.answer().get(), "the runtime answers only what it accepted");
    }
}
