package cn.managame.spring.rpc;

import cn.managame.rpc.node.RpcNode;
import org.springframework.context.SmartLifecycle;

final class RpcNodeLifecycle implements SmartLifecycle {
    private final RpcNode node;
    private volatile boolean running;
    RpcNodeLifecycle(RpcNode node) { this.node = node; }
    public int getPhase() { return Integer.MAX_VALUE; }
    public boolean isRunning() { return running; }
    public void start() { if (!running) { node.start(); running = true; } }
    public void stop() { try { node.close(); } finally { running = false; } }
}
