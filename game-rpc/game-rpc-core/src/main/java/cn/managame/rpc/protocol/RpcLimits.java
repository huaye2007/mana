package cn.managame.rpc.protocol;

public record RpcLimits(
        int maxMessageBytes, int maxMetadataBytes, int maxBodyBytes, int maxPendingCalls) {
    public static final RpcLimits DEFAULT =
            new RpcLimits(4 * 1024 * 1024, 16 * 1024, 4 * 1024 * 1024, 65536);

    public RpcLimits {
        if (maxMessageBytes < 40
                || maxMessageBytes > Integer.MAX_VALUE - 4
                || maxMetadataBytes < 0
                || maxMetadataBytes > maxMessageBytes
                || maxBodyBytes < 0
                || maxBodyBytes > maxMessageBytes
                || maxPendingCalls < 1) throw new IllegalArgumentException("Invalid RPC limits");
    }
}
