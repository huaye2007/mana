package cn.managame.rpc;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.util.HashedWheelTimer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class RpcFutureTest {
    @Test
    void independentCallStateHasOneCompletionWinnerWithoutNotifyingTheCallback() throws Exception {
        var callbacks = new AtomicInteger();
        var future =
                new RpcFuture(
                        17,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                        ignored -> callbacks.incrementAndGet());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 100; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    return future.tryComplete();
                                }));
            start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(3, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
        assertTrue(future.isDone());
        assertEquals(0, callbacks.get(), "The owner chooses when and where to deliver results");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timerHandleIsCancelledRegardlessOfInstallationOrder(boolean completeFirst) {
        var timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        try {
            var future = new RpcFuture(1, Long.MAX_VALUE, ignored -> fail("Unexpected callback"));
            var timeout =
                    timer.newTimeout(ignored -> fail("Cancelled task fired"), 1, TimeUnit.HOURS);
            if (completeFirst) assertTrue(future.tryComplete());
            future.timer(timeout);
            if (!completeFirst) assertTrue(future.tryComplete());
            assertTrue(timeout.isCancelled());
            assertFalse(future.tryComplete());
        } finally {
            timer.stop();
        }
    }
}
