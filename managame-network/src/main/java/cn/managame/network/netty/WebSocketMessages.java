package cn.managame.network.netty;

/**
 * How WebSocket data frames map to the messages application handlers see. With BINARY and TEXT a payload
 * is a ByteBuf, as on TCP, so the same codec handlers serve both transports; outbound WebSocketFrames pass
 * through unchanged in every mode.
 */
public enum WebSocketMessages {
    /** Binary frames in and out. A text frame closes the connection with status 1003. */
    BINARY,
    /** Text frames in and out, payloads as UTF-8 bytes. A binary frame closes the connection with status 1003. */
    TEXT,
    /** No conversion: handlers receive and write Binary/TextWebSocketFrame themselves. */
    FRAMES
}
