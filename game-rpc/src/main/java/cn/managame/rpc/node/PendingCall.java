package cn.managame.rpc.node;

import cn.managame.rpc.call.RpcCallback;
import io.netty.util.Timeout;

final class PendingCall {
    final int id, command;
    final RpcCallback<?> callback;
    volatile Timeout timeout;
    PendingCall(int id, int command, RpcCallback<?> callback) {
        this.id = id; this.command = command; this.callback = callback;
    }
    void cancelTimeout() {
        Timeout current = timeout;
        if (current != null) current.cancel();
    }
}

