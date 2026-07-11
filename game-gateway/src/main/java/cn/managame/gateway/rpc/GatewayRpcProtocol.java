package cn.managame.gateway.rpc;

/** RPC envelope conventions shared by the gateway and backend game servers. */
public final class GatewayRpcProtocol {
    /** busId is the gateway session id. */
    public static final byte BUS_TYPE_SESSION = 0;
    /** busId is the authenticated role id. */
    public static final byte BUS_TYPE_ROLE = 1;

    private GatewayRpcProtocol() { }
}
