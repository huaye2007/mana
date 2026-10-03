package cn.managame.runtime.context;

import cn.managame.network.connection.Connection;

public interface HandlerContext extends InvocationContext {
    Object message();
    /** Optional borrowed connection supplied by the integration layer. */
    default Connection connection() { return null; }
}
