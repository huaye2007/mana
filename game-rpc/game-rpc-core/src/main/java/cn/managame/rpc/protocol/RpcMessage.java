package cn.managame.rpc.protocol;

/** Message data. Wire type tags belong exclusively to the codec. */
public sealed interface RpcMessage
        permits RpcRequest, RpcResponse, RpcRouteMessage, RpcHandshake, RpcHeartbeat {}
