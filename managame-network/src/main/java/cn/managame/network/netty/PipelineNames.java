package cn.managame.network.netty;

import java.util.Set;

/**
 * Names of the handlers a server installs. A pipeline customizer can find, replace or insert around them;
 * application handlers cannot use them. Server pipelines are laid out as:
 *
 * <pre>
 * front handlers | ssl | http-codec, http-aggregator, ws-path, [ws-compression], ws-protocol, ws-aggregator,
 * ws-messages | application handlers | customizers | connection
 * </pre>
 *
 * TLS and WebSocket sections are present only when configured. {@code ws-path} removes itself once the path
 * matched, and Netty swaps the HTTP handlers for WebSocket frame codecs after the handshake. HTTP server
 * pipelines are laid out as:
 *
 * <pre>
 * front handlers | [ssl] | http-codec, http-flow, http-exchange, http-keep-alive, http-aggregator |
 * application handlers | http-handler
 * </pre>
 */
public final class PipelineNames {
    public static final String SSL = "ssl";
    public static final String HTTP_CODEC = "http-codec";
    public static final String HTTP_AGGREGATOR = "http-aggregator";
    public static final String WEBSOCKET_PATH = "ws-path";
    public static final String WEBSOCKET_COMPRESSION = "ws-compression";
    public static final String WEBSOCKET_PROTOCOL = "ws-protocol";
    public static final String WEBSOCKET_AGGREGATOR = "ws-aggregator";
    /** Converts between WebSocket frames and payloads according to {@link WebSocketMessages}. */
    public static final String WEBSOCKET_MESSAGES = "ws-messages";
    /** The terminal handler that drives the ConnectionHandler. */
    public static final String CONNECTION = "connection";
    /** Holds input that arrives while an HTTP request is being answered. */
    public static final String HTTP_FLOW = "http-flow";
    /** Keeps HTTP responses in request order. */
    public static final String HTTP_EXCHANGE = "http-exchange";
    public static final String HTTP_KEEP_ALIVE = "http-keep-alive";
    /** The terminal handler that drives the HttpHandler. */
    public static final String HTTP_HANDLER = "http-handler";

    /** Includes the frame codec names Netty installs during the WebSocket handshake. */
    static final Set<String> RESERVED = Set.of(SSL, HTTP_CODEC, HTTP_AGGREGATOR, WEBSOCKET_PATH, WEBSOCKET_COMPRESSION,
            WEBSOCKET_PROTOCOL, WEBSOCKET_AGGREGATOR, WEBSOCKET_MESSAGES, CONNECTION,
            HTTP_FLOW, HTTP_EXCHANGE, HTTP_KEEP_ALIVE, HTTP_HANDLER, "wsencoder", "wsdecoder");

    private PipelineNames() {}
}
