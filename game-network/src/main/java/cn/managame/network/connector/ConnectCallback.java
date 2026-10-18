package cn.managame.network.connector;

import cn.managame.network.connection.Connection;

/**
 * The outcome of one connection attempt: exactly one of these runs, once.
 *
 * <p>{@code onFail} is the name every one-shot failure branch in this framework uses, so a reader who
 * has seen one has seen them all. {@link cn.managame.network.connection.ConnectionHandler#onException}
 * deliberately keeps its own name: it is not the failure branch of an operation but a stream of errors
 * on a connection that is already established, and it pairs with no success callback.
 */
public interface ConnectCallback {
    void onSuccess(Connection connection);
    void onFail(Throwable cause);
}
