package cn.managame.runtime;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteKeyRegistry;
import cn.managame.runtime.timer.CronScheduler;
import cn.managame.runtime.timer.RuntimeTimer;
import cn.managame.runtime.http.HttpDispatcher;
import cn.managame.network.connection.Connection;

import java.util.function.Supplier;
import cn.managame.core.Metadata;
import java.time.Duration;
import cn.managame.runtime.diagnostic.RuntimeStats;
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    /** Uses the caller's Key and resolves the Handler's Domain. */
    void dispatch(Connection connection, long routeKey, Object message);
    /** Uses the caller's Key and explicit business identity; Domain comes from the Handler annotation. */
    void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Object message);
    /** Uses the configured HandlerContextFactory, or message Key extraction when no factory is configured. */
    void dispatch(Connection connection, Object message);
    void dispatch(Connection connection, Metadata metadata, Object message);
    void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Metadata metadata, Object message);
    void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey,
                     int businessIdType, long businessId, Metadata metadata, Object message);
    /** Uses explicit business identity and extracts Key from the registered message rule. */
    void dispatch(Connection connection, int businessIdType, long businessId, Object message);
    <T> void call(int routeDomain, long routeKey, Supplier<T> action, RouteCallback<T> callback);
    /** Captures the current Route for a single asynchronous success/failure; it participates in drain. */
    <T> RouteCallback<T> callback(RouteCallback<T> callback);
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    HttpDispatcher http();
    /** Stops external admission and future timers; accepted Route work can finish. */
    void shutdown();
    /** Waits for admitted Route work. Call shutdown first; never wait on this Runtime's Route. */
    boolean awaitTermination(Duration timeout) throws InterruptedException;
    RuntimeStats stats();
    void close();
}
