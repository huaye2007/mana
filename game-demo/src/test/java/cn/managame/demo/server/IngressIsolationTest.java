package cn.managame.demo.server;

import cn.managame.demo.client.DemoClient;
import cn.managame.demo.protocol.CommandBinding;
import cn.managame.demo.protocol.GameCallException;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.protocol.rpc.RpcCommands;
import cn.managame.demo.server.gameplay.*;
import cn.managame.demo.server.network.GameTcpServer;
import cn.managame.demo.server.rpc.GameRpcServer;
import cn.managame.demo.server.runtime.ServerRuntime;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.runtime.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static cn.managame.demo.protocol.client.ClientProtocol.*;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class IngressIsolationTest {
    @Test void clientSpendingAndInternalGrantsUseSeparateProtocolsOnOneWallet() throws Exception {
        try (var server = new DemoServer(20, 0, 0);
             var peer = new DemoServer(21, 0, 0);
             var client = new DemoClient()) {
            server.start(); peer.start();
            client.connect(server.clientAddress());
            peer.rpc().connectPeer(20, server.rpcAddress());
            assertError(RpcError.NO_HANDLER.code(), peer.rpc().call(20, ClientCommands.SPEND,
                    new SpendGoldReq(7, 30, 1), new Route(PlayerRoute.class, 7), RpcOptions.route(7)));
            assertEquals(new GrantGoldRes(7, 150, 0), peer.rpc().call(20, RpcCommands.GRANT,
                    new GrantGoldReq(50), new Route(PlayerRoute.class, 7), RpcOptions.route(7)).get(3, TimeUnit.SECONDS));
            assertEquals(new SpendGoldRes(7, 140, 2),
                    client.call(ClientCommands.SPEND, new SpendGoldReq(7, 10, 2)).get(3, TimeUnit.SECONDS));
            assertEquals(new GetWalletRes(7, 140),
                    client.call(ClientCommands.WALLET, new GetWalletReq(7, 3)).get(3, TimeUnit.SECONDS));
        }
    }

    @Test void wrongEntrypointAndProtocolNumberCannotExposeInternalOperations() throws Exception {
        try (var server = new DemoServer(20, 0, 0); var client = new DemoClient()) {
            server.start();
            client.connect(server.clientAddress());
            // Negative command cannot even be encoded as a client packet.
            var failure = assertThrows(ExecutionException.class, () ->
                    client.call(RpcCommands.GRANT, new GrantGoldReq(50)).get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            // Changing the ID cannot smuggle an internal body through the positive client command.
            var disguised = new CommandBinding<>(SPEND_GOLD, GrantGoldReq.class, GrantGoldRes.class);
            assertError(INVALID_REQUEST, client.call(disguised, new GrantGoldReq(50)));
            assertEquals(new GetWalletRes(7, 100),
                    client.call(ClientCommands.WALLET, new GetWalletReq(7, 1)).get(3, TimeUnit.SECONDS));
        }
    }

    @Test void incompatibleIngressDeclarationsFailBeforeTransportStarts() {
        var wallets = new Wallets();
        try (var runtime = new ServerRuntime(20,
                List.of(ClientCommands.SPEND, ClientCommands.WALLET, RpcCommands.GRANT),
                new WalletHandler(wallets), new WalletRpcHandler(wallets))) {
            assertThrows(IllegalArgumentException.class, () -> new GameRpcServer(20, 0, runtime, List.of(ClientCommands.SPEND)));
            assertThrows(IllegalArgumentException.class, () -> new GameTcpServer(0, runtime, List.of(RpcCommands.GRANT)));
            var wrongResponse = new CommandBinding<>(SPEND_GOLD, SpendGoldReq.class, String.class);
            assertThrows(IllegalArgumentException.class, () -> new GameTcpServer(0, runtime, List.of(wrongResponse)));
            var wrongRequest = new CommandBinding<>(GRANT_GOLD, GetWalletReq.class, GrantGoldRes.class);
            assertThrows(IllegalArgumentException.class, () -> new GameRpcServer(20, 0, runtime, List.of(wrongRequest)));
            assertThrows(IllegalArgumentException.class, () -> new CommandBinding<>(0, GetWalletReq.class, GetWalletRes.class));
        }
    }

    @Test void duplicateExposureCannotSilentlyOverrideAnotherRoute() {
        var wallets = new Wallets();
        try (var runtime = new ServerRuntime(20, List.of(ClientCommands.SPEND, ClientCommands.WALLET, RpcCommands.GRANT),
                new WalletHandler(wallets), new WalletRpcHandler(wallets))) {
            assertThrows(IllegalStateException.class, () -> new GameRpcServer(20, 0, runtime,
                    List.of(RpcCommands.GRANT, RpcCommands.GRANT)));
            assertThrows(IllegalStateException.class, () -> new GameTcpServer(0, runtime,
                    List.of(ClientCommands.SPEND, ClientCommands.SPEND)));
        }
    }

    private static void assertError(int code, CompletableFuture<?> call) {
        var error = assertThrows(ExecutionException.class, () -> call.get(3, TimeUnit.SECONDS));
        assertEquals(code, assertInstanceOf(GameCallException.class, error.getCause()).errorCode());
    }
}
