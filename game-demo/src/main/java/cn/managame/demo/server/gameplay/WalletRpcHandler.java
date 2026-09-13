package cn.managame.demo.server.gameplay;

import cn.managame.demo.server.support.*;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.execution.HandlerContexts;
import cn.managame.runtime.annotation.HandlerMethod;
import java.util.Objects;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;

/** Internal reward grants; client spending follows a separate protocol and handler. */
@Handler(routeType = PlayerRoute.class)
public final class WalletRpcHandler {
    private final Wallets wallets;

    public WalletRpcHandler(Wallets wallets) { this.wallets = Objects.requireNonNull(wallets); }

    @HandlerMethod
    public void grant(GrantGoldReq request, PlayerId player) {
        var wallet = wallets.get(player.value());
        if (request.amount() <= 0 || (long) wallet.gold + request.amount() > Integer.MAX_VALUE) {
            GameMessages.sendError(INVALID_GRANT, Integer.toString(request.amount()), Integer.toString(wallet.gold));
            return;
        }
        wallet.gold += request.amount();
        GameMessages.send(new GrantGoldRes(player.value(), wallet.gold,
                HandlerContexts.current().metadata().get(CommandMetadata.TRACE_ID)));
    }
}
