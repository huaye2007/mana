package cn.managame.demo.server.rpc;

import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.runtime.execution.GameRuntime;
import cn.managame.runtime.route.Route;

import static cn.managame.demo.DemoCalls.*;

import cn.managame.demo.protocol.GameCallException;
import cn.managame.demo.server.DemoServer;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.demo.protocol.rpc.RpcCommands;
import cn.managame.network.ConnectCallback;
import cn.managame.network.Connection;
import cn.managame.rpc.core.*;
import cn.managame.runtime.execution.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.*;

import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RpcRuntimeTcpTest {
    @Test void realRpcTimeoutCompletesTheAdaptedCall() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (GameRuntime runtime = GameRuntime.builder().build();
             RpcNode target = RpcNode.builder().nodeId(20).listen("127.0.0.1", 0).defaultTimeout(Duration.ofSeconds(3))
                     .handler((connection, message) -> received.countDown()).build();
             RpcNode source = RpcNode.builder().nodeId(21).listen("127.0.0.1", 0).defaultTimeout(Duration.ofSeconds(3))
                     .handler((connection, message) -> {}).build();
             RpcRuntimeClient calls = new RpcRuntimeClient(runtime, source)) {
            target.start(); source.start();
            connect(source, target);
            var result = calls.call(20, RpcCommands.GRANT, new GrantGoldReq(1), new Route(PlayerRoute.class, 7),
                    RpcOptions.builder().routeKey(7).timeout(Duration.ofMillis(150)).build());
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(RpcError.TIMEOUT.code(), failure(result).errorCode());
        }
    }

    @Test void serverShutdownSettlesAnUnansweredCallBeforeClosingItsRuntime() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (RpcNode target = RpcNode.builder().nodeId(20).listen("127.0.0.1", 0).defaultTimeout(Duration.ofSeconds(3))
                    .handler((connection, message) -> received.countDown()).build();
             DemoServer source = new DemoServer(21, 0, 0)) {
            target.start(); source.start();
            source.rpc().connectPeer(target.nodeId(), target.localAddress());
            var result = grantOnPeer(source, 20, 7, 1, 123);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            source.close();
            assertTrue(result.isDone());
            assertEquals(RpcError.UNAVAILABLE.code(), failure(result).errorCode());
        }
    }

    private static void connect(RpcNode source, RpcNode target) throws Exception {
        var ready = new CompletableFuture<Void>();
        source.connect(target.nodeId(), "127.0.0.1", target.localAddress().getPort(), new ConnectCallback() {
            public void onSuccess(Connection connection) { ready.complete(null); }
            public void onFailure(Throwable failure) { ready.completeExceptionally(failure); }
        });
        ready.get(3, TimeUnit.SECONDS);
    }

    private static GameCallException failure(CompletableFuture<?> future) {
        var error = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        return assertInstanceOf(GameCallException.class, error.getCause());
    }
}
