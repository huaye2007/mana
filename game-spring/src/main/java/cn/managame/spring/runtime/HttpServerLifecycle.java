package cn.managame.spring.runtime;

import cn.managame.network.http.HttpServer;
import org.springframework.context.SmartLifecycle;

/** Starts only after singleton assembly; ContextClosedEvent drains Runtime before lifecycle stop. */
final class HttpServerLifecycle implements SmartLifecycle {
    private final HttpServer server;
    private final boolean enabled;
    private volatile boolean running;

    HttpServerLifecycle(HttpServer server, boolean enabled) { this.server = server; this.enabled = enabled; }
    @Override public boolean isAutoStartup() { return enabled; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public boolean isRunning() { return running; }
    @Override public void start() {
        if (running || !enabled) return;
        server.start();
        running = true;
    }
    @Override public void stop() {
        try { server.close(); } finally { running = false; }
    }
}
