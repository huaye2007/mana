package cn.managame.rpc;

import cn.managame.rpc.connection.RpcConnection;
import cn.managame.serialization.SerializerManager;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.HashedWheelTimer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcInvokeConcurrencyTest {

    @Test
    void pendingSoftLimitRejectsWhenAlreadyReached() {
        TestContainer container = new TestContainer(1);
        EmbeddedChannel channel = new EmbeddedChannel();
        RpcConnection connection = new RpcConnection(channel);
        RpcInvokeManager manager = new RpcInvokeManager(container);
        try {
            RpcFuture accepted = manager.invoke(connection,
                    RpcRequest.of(1).timeoutMillis(30_000).body(new byte[0]));
            RpcFuture rejected = manager.invoke(connection,
                    RpcRequest.of(1).timeoutMillis(30_000).body(new byte[0]));

            assertFalse(manager.isIdle());
            assertFalse(accepted.isDone());
            assertTrue(rejected.isDone());
            assertEquals(1, container.getMetrics().rejectedPendingLimit());
        } finally {
            manager.failAll(new GameRpcException("test cleanup"));
            container.stopTimer();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void stoppedTimerFailsInvokeWithoutLeavingItInFlight() {
        TestContainer container = new TestContainer(10);
        EmbeddedChannel channel = new EmbeddedChannel();
        RpcConnection connection = new RpcConnection(channel);
        RpcInvokeManager manager = new RpcInvokeManager(container);
        try {
            container.stopTimer();
            RpcFuture future = manager.invoke(connection,
                    RpcRequest.of(1).timeoutMillis(30_000).body(new byte[0]));
            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertTrue(manager.isIdle());
        } finally {
            manager.failAll(new GameRpcException("test cleanup"));
            container.stopTimer();
            channel.finishAndReleaseAll();
        }
    }

    private static final class TestContainer extends RpcContainer {
        TestContainer(int maxPending) {
            serializerManager = SerializerManager.createDefault();
            sharedTimer = new HashedWheelTimer();
            maxPendingPerPeer = maxPending;
        }
        void stopTimer() {
            sharedTimer.stop();
        }
    }
}
