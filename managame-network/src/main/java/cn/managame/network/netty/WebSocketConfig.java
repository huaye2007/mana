package cn.managame.network.netty;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * WebSocket endpoint settings, immutable; each {@code with} method returns a copy.
 *
 * @param path exact request path, for example {@code /game}; a query string is allowed and kept in the
 *        handshake attribute, other paths are answered with 404
 * @param maxMessageSize largest message after reassembling fragments, in bytes; larger ones close the connection
 * @param messages frame mapping, BINARY by default
 * @param subprotocols subprotocols the server accepts, empty for none
 * @param compression whether to negotiate permessage-deflate
 */
public record WebSocketConfig(String path, int maxMessageSize, WebSocketMessages messages,
                              List<String> subprotocols, boolean compression) {
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 1024 * 1024;

    public WebSocketConfig {
        checkPath(path);
        if (maxMessageSize <= 0) throw new IllegalArgumentException("maxMessageSize must be positive");
        Objects.requireNonNull(messages, "messages");
        subprotocols = List.copyOf(subprotocols);
        for (String subprotocol : subprotocols)
            if (subprotocol.isBlank() || subprotocol.contains(","))
                throw new IllegalArgumentException("Invalid subprotocol: " + subprotocol);
    }

    public static WebSocketConfig of(String path) {
        return new WebSocketConfig(path, DEFAULT_MAX_MESSAGE_SIZE, WebSocketMessages.BINARY, List.of(), false);
    }

    public WebSocketConfig withMaxMessageSize(int bytes) { return new WebSocketConfig(path, bytes, messages, subprotocols, compression); }
    public WebSocketConfig withMessages(WebSocketMessages mode) { return new WebSocketConfig(path, maxMessageSize, mode, subprotocols, compression); }
    public WebSocketConfig withSubprotocols(String... values) { return new WebSocketConfig(path, maxMessageSize, messages, List.of(values), compression); }
    public WebSocketConfig withCompression(boolean enabled) { return new WebSocketConfig(path, maxMessageSize, messages, subprotocols, enabled); }

    private static void checkPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("?") || path.contains("#") || path.contains("*"))
            throw new IllegalArgumentException("WebSocket path must be absolute, without query, fragment or wildcard: " + path);
        URI uri;
        try { uri = URI.create(path); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid WebSocket path: " + path, e); }
        if (uri.getRawAuthority() != null || !path.equals(uri.getRawPath()))
            throw new IllegalArgumentException("Invalid WebSocket path: " + path);
    }
}
