package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataKey;
import cn.managame.runtime.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ExplicitMetadataTest {
    private static final Route PLAYER = new Route(TestRoutes.Player.class, 7);
    private static final MetadataKey<String> TRACE = MetadataKey.application(901, String.class);

    @Test void explicitDispatchUsesSuppliedMetadataWithoutConsultingTheCurrentContext() throws Exception {
        var metadata = Metadata.empty().with(TRACE, "transport");
        var observed = new AtomicReference<HandlerContext>();
        try (var runtime = GameRuntime.builder().metadataPropagator(parent -> {
            throw new AssertionError("Explicit metadata must not be propagated again");
        }).build()) {
            runtime.dispatch(PLAYER.type(), PLAYER.key(), metadata, () ->
                    observed.set(HandlerContexts.current())).get(3, TimeUnit.SECONDS);
            assertEquals(PLAYER, observed.get().route());
            assertSame(metadata, observed.get().metadata());
        }
    }

    @Test void explicitSuccessAndFailureCallbacksKeepTheSuppliedMetadata() throws Exception {
        var metadata = Metadata.empty().with(TRACE, "rpc");
        var successContext = new AtomicReference<HandlerContext>();
        var failureContext = new AtomicReference<HandlerContext>();
        var result = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        var expectedFailure = new IllegalStateException("remote");
        try (var runtime = GameRuntime.builder().metadataPropagator(parent -> {
            throw new AssertionError("Explicit metadata must not inherit the transport thread");
        }).build()) {
            var success = runtime.<String>callback(PLAYER, metadata, value -> {
                successContext.set(HandlerContexts.current());
                result.set(value);
            }, null);
            assertTrue(success.onSuccess("owned response"));
            success.completion().get(3, TimeUnit.SECONDS);

            var rejected = runtime.<String>callback(PLAYER, metadata, value -> fail(), error -> {
                failureContext.set(HandlerContexts.current());
                failure.set(error);
            });
            assertTrue(rejected.onFail(expectedFailure));
            rejected.completion().get(3, TimeUnit.SECONDS);
            assertEquals("owned response", result.get());
            assertSame(expectedFailure, failure.get());
            assertEquals(PLAYER, successContext.get().route());
            assertEquals(PLAYER, failureContext.get().route());
            assertSame(metadata, successContext.get().metadata());
            assertSame(metadata, failureContext.get().metadata());
        }
    }

    @Test void explicitCallbacksUseReservedCapacityWhileOrdinaryAdmissionIsFull() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observed = new AtomicReference<String>();
        try (var runtime = GameRuntime.builder()
                .executionDomain(PLAYER.type(), ExecutionDomain.virtual("reserved")
                        .maxTasks(1).maxTasksPerRoute(1).callbackReserve(1, 1).build()).build()) {
            var blocked = runtime.dispatch(PLAYER.type(), PLAYER.key(), () -> {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timed out");
                } catch (InterruptedException error) { throw new AssertionError(error); }
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var callback = runtime.<String>callback(PLAYER, Metadata.empty(), observed::set, null);
                assertTrue(callback.onSuccess("reserved"));
                assertFalse(callback.completion().isDone());
                release.countDown();
                callback.completion().get(3, TimeUnit.SECONDS);
                assertEquals("reserved", observed.get());
            } finally { release.countDown(); }
            blocked.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void explicitEntryPointsRejectMissingContextOrUnboundRoutesBeforeSubmission() {
        try (var runtime = GameRuntime.builder()
                .executionDomain(PLAYER.type(), ExecutionDomain.virtual("players").build()).build()) {
            assertThrows(NullPointerException.class, () -> runtime.dispatch(null, 1, Metadata.empty(), () -> {}));
            assertThrows(NullPointerException.class, () -> runtime.dispatch(PLAYER.type(), 1, null, () -> {}));
            assertThrows(NullPointerException.class, () -> runtime.dispatch(PLAYER.type(), 1, Metadata.empty(), null));
            assertThrows(NullPointerException.class, () -> runtime.callback(null, Metadata.empty(), value -> {}, null));
            assertThrows(NullPointerException.class, () -> runtime.callback(PLAYER, null, value -> {}, null));
            assertThrows(NullPointerException.class, () -> runtime.callback(PLAYER, Metadata.empty(), null, null));
            assertThrows(IllegalArgumentException.class, () -> runtime.dispatch(TestRoutes.Guild.class, 1, Metadata.empty(), () -> {}));
            assertThrows(IllegalArgumentException.class, () -> runtime.callback(
                    new Route(TestRoutes.Guild.class, 1), Metadata.empty(), value -> {}, null));
            assertEquals(0, runtime.metrics().outstandingTasks());
        }
    }
}
