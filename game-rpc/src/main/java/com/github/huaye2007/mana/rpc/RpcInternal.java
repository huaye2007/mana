package com.github.huaye2007.mana.rpc;

/**
 * 框架内部命令与保留 metadata key。内部命令落在 {@link RpcRequest#INTERNAL_COMMAND_MIN}~
 * {@link RpcRequest#INTERNAL_COMMAND_MAX} 段，业务 handler 永远看不到；保留 metadata key
 * 小于 {@link com.github.huaye2007.mana.rpc.protocol.Metadata#KEY_BUSINESS_MIN}。
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

    public static final short META_SERVICE_NAME = 1;
    public static final short META_SERVICE_ID = 2;
    public static final short META_AUTH_TOKEN = 3;

    private RpcInternal() {
    }
}
