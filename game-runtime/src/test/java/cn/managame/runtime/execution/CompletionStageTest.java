package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CompletionStageTest {
    @Test void externalCompletionAndCancellationCannotChangeRouteTask() throws Exception {
        var release = new CountDownLatch(1);
        try (var runtime = GameRuntime.builder().build()) {
            try {
                var task = runtime.dispatch(TestRoutes.Player.class, 7, () -> {
                    try { release.await(); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                });
                var stage = task.completionStage();
                assertTrue(stage.toCompletableFuture().complete(null));
                assertTrue(stage.toCompletableFuture().cancel(false));
                var observed = stage.toCompletableFuture();
                assertFalse(observed.isDone());
                assertFalse(task.isDone());
                release.countDown();
                observed.get(3, TimeUnit.SECONDS);
                task.get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    @Test void infrastructureSeesFailureEvenWhenRuntimeCannotAcceptNotifications() {
        var runtime = GameRuntime.builder().exceptionHandler(error -> {}).build();
        var callback = runtime.callback(new CallbackDefinition<String>(new Route(TestRoutes.Player.class, 1), value -> fail(), null));
        var stage = callback.completion().completionStage().toCompletableFuture();
        runtime.close();
        var rejected = assertThrows(HandlerException.class, () -> callback.onSuccess("late"));
        var error = assertThrows(ExecutionException.class, () -> stage.get(3, TimeUnit.SECONDS));
        assertSame(rejected, error.getCause());
    }

    @Test void observerFailureCannotReplaceTaskResult() throws Exception {
        try (var runtime = GameRuntime.builder().build()) {
            var task = runtime.dispatch(TestRoutes.Player.class, 7, () -> {});
            var observer = task.completionStage().thenRun(() -> { throw new IllegalStateException("observer"); });
            assertThrows(ExecutionException.class, () -> observer.toCompletableFuture().get(3, TimeUnit.SECONDS));
            task.get(3, TimeUnit.SECONDS);
            assertNull(task.completionStage().toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
    }
}
