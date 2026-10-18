package cn.managame.runtime;

import cn.managame.core.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.diagnostic.RuntimeStats;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.http.HttpDispatcher;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.CapturedRouteCallback;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteKeyRegistry;
import cn.managame.runtime.timer.CronScheduler;
import cn.managame.runtime.timer.RuntimeTimer;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Route execution facade. Work enters as an explicitly built context, through the client ingress, or
 * through the RPC ingress. The client ingress has two forms, and they differ in who chooses the Route:
 * the caller, by supplying the Key and identity it already holds, or a configured factory, which Runtime
 * hands the Handler's Domain. Neither is a shorthand for the other. No entry takes a Route Key and a
 * business identity in shapes that read alike, so no call site needs a comment to say which number is
 * which. Every rejection throws {@link cn.managame.runtime.error.RuntimeDispatchException} carrying a
 * framework error code.
 */
public interface GameRuntime extends AutoCloseable {
    /**
     * Routing, identity and any transport capability are whatever the caller put in the context. This is
     * the entry for a caller that decides the Route itself: {@link cn.managame.runtime.context.DefaultClientHandlerContext}
     * names each value it takes, where an overload taking a Connection and two numbers could not.
     */
    void dispatch(HandlerContext context);

    /**
     * The client ingress for an integration that already knows the Route and the authenticated identity
     * but should not have to know the Domain: Runtime resolves that from the message's registered Handler,
     * because it is business configuration and not something a packet decoder should carry. Bypasses any
     * configured HandlerContextFactory, and expects no response — a request that can be answered arrives
     * through the correlation entry below or as an explicitly built context.
     */
    void dispatch(Connection connection, long routeKey, int businessIdType, long businessId,
                  Metadata metadata, Object message);

    /**
     * The client ingress. Uses the configured HandlerContextFactory, or message Key extraction when no
     * factory is configured. {@code requestId} is the integration's correlation: zero expects no response,
     * nonzero expects one, which the Handler either returns — Runtime frames and writes it — or sends itself
     * through the context's connection.
     */
    void dispatch(Connection connection, int command, int requestId, Metadata metadata, Object message);

    /** The same ingress for a request that expects no response. */
    default void dispatch(Connection connection, Metadata metadata, Object message) {
        dispatch(connection, 0, 0, metadata, message);
    }

    /** The same ingress with empty Metadata and no correlation. */
    default void dispatch(Connection connection, Object message) {
        dispatch(connection, 0, 0, Metadata.empty(), message);
    }

    void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey,
                     int businessIdType, long businessId, Metadata metadata, Object message);

    <T> void call(int routeDomain, long routeKey, Supplier<T> action, RouteCallback<T> callback);

    /** Captures the current Route for a single asynchronous success/failure; it participates in drain. */
    <T> CapturedRouteCallback<T> callback(RouteCallback<T> callback);

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
