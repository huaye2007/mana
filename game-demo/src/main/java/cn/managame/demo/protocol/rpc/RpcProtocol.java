package cn.managame.demo.protocol.rpc;

/** Internal service contracts; never exposed on the client TCP port. */
public final class RpcProtocol {
    public static final int GRANT_GOLD = -1001;
    public static final int INVALID_GRANT = 3001;
    public static final short TRACE_ID = 1024;

    public record GrantGoldReq(int amount) {}
    public record GrantGoldRes(long playerId, int gold, long traceId) {}

    private RpcProtocol() {}
}
