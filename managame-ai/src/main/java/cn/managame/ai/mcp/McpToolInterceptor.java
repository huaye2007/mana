package cn.managame.ai.mcp;

import java.util.Map;

/**
 * Intercepts MCP tool discovery ({@link #allowList}) and invocation ({@link #before} / {@link #after}) for
 * authorization, read-only enforcement, feature switches and audit logging.
 *
 * <p>{@code before} runs in registration order on the tool executor's thread; returning {@code false} skips later
 * interceptors and the tool itself, answering with {@link cn.managame.FrameworkErrorCodes#AI_TOOL_REJECTED} unless the
 * interceptor already answered through {@code call.context()}. {@code after} runs in reverse order for every
 * interceptor whose {@code before} returned {@code true} once the call finishes (synchronously or asynchronously).
 */
public interface McpToolInterceptor {
    /** Whether {@code tool} is visible to a caller with {@code headers} during MCP {@code tools/list}. */
    default boolean allowList(Map<String, String> headers, McpToolDefinition tool) { return true; }

    /** Runs before the tool; return {@code false} to refuse the call. */
    default boolean before(McpToolCall call) throws Exception { return true; }

    /** Runs after the tool completes with its final {@code result}, or after a later interceptor refused or threw. */
    default void after(McpToolCall call, McpToolResult result) throws Exception {}
}
