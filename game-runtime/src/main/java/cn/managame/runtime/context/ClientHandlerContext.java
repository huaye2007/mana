package cn.managame.runtime.context;

import cn.managame.network.connection.Connection;

/**
 * Client-origin message context, independent of transport; the connection is borrowed and may be absent.
 * Correlation mirrors {@link RpcHandlerContext}: the integration supplies the inbound command and a request
 * id that is zero when the request expects no response and nonzero when exactly one response is expected.
 */
public interface ClientHandlerContext extends HandlerContext {
    Connection connection();

    /** Registered command of the inbound message, as the integration decoded it; 0 when it carries none. */
    int command();

    /** Zero when no response is expected; nonzero correlates the one response this request may receive. */
    int requestId();
}
