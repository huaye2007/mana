package cn.managame.rpc.error;

import cn.managame.core.FrameworkErrorCodes;

/** RPC aliases of the shared Core allocation. No wire high-bit transformation. */
public final class RpcErrorCodes {
    private RpcErrorCodes() {}
    public static final int PEER_NOT_FOUND = FrameworkErrorCodes.RPC_PEER_NOT_FOUND;
    public static final int UNAVAILABLE = FrameworkErrorCodes.RPC_UNAVAILABLE;
    public static final int TIMEOUT = FrameworkErrorCodes.RPC_TIMEOUT;
    public static final int PEER_REMOVED = FrameworkErrorCodes.RPC_PEER_REMOVED;
    public static final int NODE_CLOSED = FrameworkErrorCodes.RPC_NODE_CLOSED;
    public static final int HANDLER_ERROR = FrameworkErrorCodes.RPC_HANDLER_ERROR;
    public static final int PROTOCOL_ERROR = FrameworkErrorCodes.RPC_PROTOCOL_ERROR;
}

