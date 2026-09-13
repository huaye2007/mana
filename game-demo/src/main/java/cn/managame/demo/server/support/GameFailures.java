package cn.managame.demo.server.support;

import cn.managame.demo.protocol.GameCallException;
import cn.managame.runtime.diagnostics.RuntimeClosedException;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;

import java.util.List;

import cn.managame.rpc.protocol.RpcError;

/** Shared local-failure mapping for command admission, execution and RPC callback delivery. */
public final class GameFailures {
    public static GameCallException from(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof GameCallException game) return game;
            if (cause instanceof RuntimeOverloadedException) return new GameCallException(RpcError.OVERLOADED.code(), List.of(), failure);
            if (cause instanceof RuntimeClosedException) return new GameCallException(RpcError.UNAVAILABLE.code(), List.of(), failure);
        }
        return new GameCallException(RpcError.INTERNAL_ERROR.code(), List.of(), failure);
    }
    private GameFailures() {}
}
