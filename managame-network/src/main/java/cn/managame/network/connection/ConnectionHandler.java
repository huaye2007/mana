package cn.managame.network.connection;

/**
 * Application callbacks for established connections. One instance serves every connection of a server, so
 * per-connection state belongs in Connection attributes or in the application's own Connection type. Every
 * callback of a connection runs on that connection's EventLoop, in order, and must not block. The Connection
 * passed is whatever the transport's channel-to-connection mapping returns at that moment.
 */
public interface ConnectionHandler {
    /** Transport established: after TCP accept, the TLS handshake and the WebSocket handshake, as configured. */
    void onActive(Connection connection);

    /**
     * A message produced by the pipeline's handlers. It is released after this call returns; retain a
     * reference-counted message to keep it longer.
     */
    void onRead(Connection connection, Object message);

    /**
     * At most once after onActive, when the channel becomes inactive; skipped only when the mapping no longer
     * returns a connection. Never called without onActive.
     */
    void onInActive(Connection connection);

    /** User events from the pipeline, for example IdleStateEvent; establishment events are not delivered. */
    default void onEvent(Connection connection, Object event) {}

    /** Exceptions after onActive, including those thrown by the other callbacks. The connection stays open. */
    void onException(Connection connection, Throwable cause);
}
