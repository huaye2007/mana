package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.util.AttributeKey;

/** Connection attributes set by the transport. */
public final class NetworkAttributes {
    /**
     * Where the default {@link ConnectionMapper#attribute()} keeps a channel's connection, from just before
     * onActive until just after onInActive. A custom mapper decides for itself whether and where to store it.
     */
    public static final AttributeKey<Connection> CONNECTION = AttributeKey.valueOf("cn.managame.network.connection");
    /**
     * The WebSocket upgrade request: URI with query string, headers and the selected subprotocol. Set before
     * onActive on ws/wss connections, for example to read a login token or a proxy's forwarded address.
     */
    public static final AttributeKey<HandshakeComplete> WEBSOCKET_HANDSHAKE =
            AttributeKey.valueOf("cn.managame.network.websocketHandshake");

    private NetworkAttributes() {}
}
