package cn.managame.spring.rpc;

import org.springframework.context.SmartLifecycle;

final class RpcNodeLifecycle implements SmartLifecycle {
    private final GameRpc rpc;
    private volatile boolean running;
    RpcNodeLifecycle(GameRpc rpc) { this.rpc = rpc; }
    public int getPhase() { return Integer.MAX_VALUE; }
    public boolean isRunning() { return running; }
    public void start() { if (!running) { rpc.node().start(); running = true; } }
    /** Detaches configurers, then closes the Node; destruction later repeats close idempotently. */
    public void stop() { try { rpc.close(); } finally { running = false; } }
}
