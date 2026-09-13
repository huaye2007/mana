package cn.managame.demo.server;

import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.diagnostics.RuntimeShutdownException;
import cn.managame.runtime.route.Route;

import cn.managame.demo.client.DemoClient;
import cn.managame.demo.protocol.GameCallException;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.protocol.rpc.RpcCommands;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import cn.managame.demo.server.runtime.ServerRuntime;
import cn.managame.demo.server.support.GameMessages;
import cn.managame.demo.server.support.PlayerId;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.runtime.execution.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.*;

import static cn.managame.demo.protocol.client.ClientProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ServerShutdownTest {
    @Handler(routeType = PlayerRoute.class)
    public static final class BlockingWallet {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @HandlerMethod public void grant(GrantGoldReq request, PlayerId player) throws InterruptedException {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test handler timed out");
            GameMessages.send(new GrantGoldRes(player.value(), 110, 0));
        }
        @HandlerMethod public void wallet(GetWalletReq request, PlayerId player) throws InterruptedException {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test handler timed out");
            GameMessages.send(new GetWalletRes(player.value(), 100));
        }
    }

    @Test void drainTimeoutRetainsTcpAndRpcRepliesAndCloseCanBeRetried() throws Exception {
        for (boolean internal : new boolean[] { false, true }) {
            var handler = new BlockingWallet();
            var runtime = new ServerRuntime(20, java.util.List.of(ClientCommands.SPEND, ClientCommands.WALLET, RpcCommands.GRANT), handler);
            var server = new DemoServer(20, 0, 0, runtime);
            try (var client = new DemoClient(); var peer = new DemoServer(21, 0, 0)) {
                server.start();
                peer.start();
                client.connect(server.clientAddress());
                peer.rpc().connectPeer(server.nodeId(), server.rpcAddress());
                var response = internal
                        ? peer.rpc().call(20, RpcCommands.GRANT, new GrantGoldReq(10),
                                new Route(PlayerRoute.class, 7), RpcOptions.route(7))
                        : client.call(ClientCommands.WALLET, new GetWalletReq(7, 1));
                assertTrue(handler.entered.await(3, TimeUnit.SECONDS));
                assertThrows(RuntimeShutdownException.class, () -> server.close(Duration.ZERO));
                var rejected = client.call(ClientCommands.WALLET, new GetWalletReq(8, 2));
                var failure = assertThrows(ExecutionException.class, () -> rejected.get(3, TimeUnit.SECONDS));
                assertEquals(RpcError.UNAVAILABLE.code(), assertInstanceOf(GameCallException.class, failure.getCause()).errorCode());
                assertFalse(response.isDone());
                handler.release.countDown();
                assertEquals(internal ? new GrantGoldRes(7, 110, 0) : new GetWalletRes(7, 100), response.get(3, TimeUnit.SECONDS));
                server.close(Duration.ofSeconds(3));
            } finally {
                handler.release.countDown();
                server.close();
            }
        }
    }
}
