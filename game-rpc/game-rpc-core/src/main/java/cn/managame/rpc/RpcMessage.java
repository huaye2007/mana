package cn.managame.rpc;

/** Message data. Wire type tags belong exclusively to the codec. */
public sealed interface RpcMessage
        permits RpcRequest, RpcResponse, RpcRouteMessage, RpcHandshake, RpcHeartbeat {}
