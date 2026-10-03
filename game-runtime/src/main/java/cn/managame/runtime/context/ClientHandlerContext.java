package cn.managame.runtime.context;

import cn.managame.network.connection.Connection;

/** Client-origin message context, independent of transport; the connection is borrowed and may be absent. */
public interface ClientHandlerContext extends HandlerContext {
    Connection connection();
}
