package cn.managame.rpc;

/**
 * 框架内部命令。内部命令落在 {@link RpcRequest#INTERNAL_COMMAND_MIN}~
 * {@link RpcRequest#INTERNAL_COMMAND_MAX} 段，业务 handler 永远看不到。
 * rpc 握手/响应用的保留 metadata key 见 {@link cn.managame.common.context.MetadataKeys}。
 */
public final class RpcInternal {

    /** 握手请求：连接建立后客户端上报自身身份（oneway）。 */
    public static final int CMD_HANDSHAKE = -1;

    /** 握手确认：服务端登记对端后回执，客户端收到才把连接挂入 peer（oneway）。 */
    public static final int CMD_HANDSHAKE_ACK = -2;

    /** 心跳 ping：客户端写空闲时发，服务端回 pong（oneway）。 */
    public static final int CMD_HEARTBEAT_PING = -3;

    /** 心跳 pong：服务端对 ping 的回执（oneway）。 */
    public static final int CMD_HEARTBEAT_PONG = -4;

    private RpcInternal() {
    }
}
