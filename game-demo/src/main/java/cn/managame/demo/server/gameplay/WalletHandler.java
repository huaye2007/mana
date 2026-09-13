package cn.managame.demo.server.gameplay;

import cn.managame.demo.server.support.*;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.execution.HandlerContexts;

import java.util.Objects;

import static cn.managame.demo.protocol.client.ClientProtocol.*;

/** Client wallet operations; responses are explicitly sent on the current command's connection. */
@Handler(routeType = PlayerRoute.class)
public final class WalletHandler {
    private final Wallets wallets;

    public WalletHandler(Wallets wallets) { this.wallets = Objects.requireNonNull(wallets); }

    @HandlerMethod
    public void wallet(GetWalletReq request, PlayerId player) {
        var wallet = wallets.get(player.value());
        GameMessages.send(new GetWalletRes(player.value(), wallet.gold));
    }

    @HandlerMethod
    public void spend(SpendGoldReq request, PlayerId player) {
        if (request.amount() <= 0) {
            GameMessages.sendError(INVALID_REQUEST);
            return;
        }
        var wallet = wallets.get(player.value());
        if (wallet.gold < request.amount()) {
            GameMessages.sendError(NOT_ENOUGH_GOLD,
                    Integer.toString(request.amount()), Integer.toString(wallet.gold));
            return;
        }
        wallet.gold -= request.amount();
        GameMessages.send(new SpendGoldRes(player.value(), wallet.gold,
                HandlerContexts.current().metadata().get(CommandMetadata.TRACE_ID)));
    }
}
