package cn.managame.demo.protocol.client;

import java.util.List;

/** Application messages and business error codes. DTOs contain no server dispatch logic. */
public final class ClientProtocol {
    public static final int SPEND_GOLD = 1001;
    public static final int GET_WALLET = 1002;
    public static final int WALLET_CHANGED = 2001;
    public static final int INVALID_REQUEST = 1001;
    public static final int NOT_ENOUGH_GOLD = 1002;

    /** Common request data for client player requests. */
    public interface PlayerRequest {
        long playerId();
        long traceId();
    }

    public record SpendGoldReq(long playerId, int amount, long traceId) implements PlayerRequest {}
    public record SpendGoldRes(long playerId, int gold, long traceId) {}

    public record GetWalletReq(long playerId, long traceId) implements PlayerRequest {}
    public record GetWalletRes(long playerId, int gold) {}

    public record WalletChangedNotify(long playerId, int gold) {}

    public record ErrorRes(List<String> args) {
        public ErrorRes { args = List.copyOf(args); }
    }

    private ClientProtocol() {}
}
