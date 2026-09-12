package cn.managame.rpc;

public record RpcDiagnostic(
        String event,
        int localNodeId,
        Integer peerNodeId,
        Integer requestId,
        String connectionId,
        Throwable cause) {}
