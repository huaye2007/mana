package cn.managame.network.connection;

/**
 * Approximate counts for one listener or client. Every field but {@code liveConnections} only rises while
 * the process runs, so a reader derives a rate by differencing two readings. The counts are read without a
 * lock and are not a barrier: {@code established} and {@code closed} can be observed one apart from
 * {@code liveConnections} while a connection is being handed over.
 *
 * @param liveConnections connections currently handed to the application and not yet disconnected
 * @param accepted channels the listener accepted; always 0 for a client, which does not accept
 * @param established connections the application's onConnected was called for, after TLS and WebSocket
 * @param closed established connections whose onDisconnected was called
 * @param establishmentFailures channels that never reached onConnected: TLS, WebSocket, pipeline or a close
 *        during establishment. The gap between accepted and established is this plus what is still in flight
 * @param messagesRead inbound messages delivered to onMessage
 * @param writesAccepted writes Netty took ownership of; this is admission, not delivery
 * @param writesInactive writes refused because the connection was already inactive
 * @param writesNotWritable writes refused because the channel was over its write-buffer high watermark —
 *        the reading that explains messages disappearing under load
 * @param handlerExceptions throwables reported to onException or logged outside a connection's lifecycle
 */
public record NetworkStats(long liveConnections, long accepted, long established, long closed,
                           long establishmentFailures, long messagesRead, long writesAccepted,
                           long writesInactive, long writesNotWritable, long handlerExceptions) {}
