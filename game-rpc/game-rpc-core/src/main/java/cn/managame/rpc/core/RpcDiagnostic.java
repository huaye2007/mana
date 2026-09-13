package cn.managame.rpc.core;

public record RpcDiagnostic(
        String event,
        int localNodeId,
        Integer peerNodeId,
        Integer requestId,
        String connectionId,
        Throwable cause) {}
