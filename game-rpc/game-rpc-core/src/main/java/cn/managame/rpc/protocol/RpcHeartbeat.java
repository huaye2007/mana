package cn.managame.rpc.protocol;

import java.util.Objects;

/** Physical connection probe; managed by RpcNode and never delivered to business handlers. */
public record RpcHeartbeat(Kind kind, int sequence) implements RpcMessage {
    public enum Kind {
        PING,
        PONG
    }

    public RpcHeartbeat {
        Objects.requireNonNull(kind, "kind");
        if (sequence <= 0) throw new RpcProtocolException("Heartbeat sequence must be positive");
    }
}
