package cn.managame.demo;

import cn.managame.demo.client.DemoClient;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.server.DemoServer;
import cn.managame.demo.protocol.rpc.RpcCommands;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.runtime.route.Route;

import java.util.concurrent.CompletableFuture;

import static cn.managame.demo.protocol.client.ClientProtocol.*;

/** Wallet scenarios used by tests; production transport APIs remain generic. */
public final class DemoCalls {
    public static CompletableFuture<SpendGoldRes> spend(DemoClient client, long player, int amount, long trace) {
        return client.call(ClientCommands.SPEND, new SpendGoldReq(player, amount, trace));
    }
    public static CompletableFuture<GrantGoldRes> grantOnPeer(DemoServer server, int peer, long player, int amount, long trace) {
        return server.rpc().call(peer, RpcCommands.GRANT, new GrantGoldReq(amount),
                new Route(PlayerRoute.class, player), RpcOptions.builder().routeKey(player).putLong(TRACE_ID, trace).build());
    }
    private DemoCalls() {}
}
