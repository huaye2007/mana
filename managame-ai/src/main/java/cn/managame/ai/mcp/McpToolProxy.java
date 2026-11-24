package cn.managame.ai.mcp;

/**
 * Handles MCP tool invocations registered from {@link Class} contracts via
 * {@link McpTools.Builder#registerProxy(java.util.Collection, McpToolProxy)} without instantiating the target service
 * (for example on a stateless gateway node that forwards calls over {@code managame-rpc} using
 * {@link McpToolCall#routeKey()}).
 *
 * <p>Receives the full {@link McpToolCall} — including the compiled {@link McpToolCall#routeKey()}, decoded
 * {@link McpToolCall#arguments()}, raw argument map, {@link McpToolCall#method()}, and {@link McpToolCall#context()}
 * — and completes the call synchronously or asynchronously via {@link McpToolContext#reply(Object)} or
 * {@link McpToolContext#fail(int, String)}.
 */
@FunctionalInterface
public interface McpToolProxy {

    void handle(McpToolCall call) throws Exception;
}
