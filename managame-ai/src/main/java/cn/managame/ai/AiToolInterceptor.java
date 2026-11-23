package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;
import java.util.Map;

/**
 * Intercepts tool discovery and execution in {@link AiTools}. Use it for authentication, per-caller group visibility,
 * read-only enforcement, feature switches and audit logging.
 */
public interface AiToolInterceptor {
    /**
     * Whether {@code tool} should be included in {@link AiTools#list(Map)} (such as {@code GET /tools}) for a caller
     * presenting {@code headers}. Defaults to {@code true}.
     */
    default boolean allowList(Map<String, String> headers, AiToolDefinition tool) { return true; }

    /**
     * Runs on the tool's execution thread right before the tool method. Return {@code false} to refuse execution; the
     * interceptor may answer via {@code context.fail(...)}, otherwise {@link AiTools} answers with
     * {@link FrameworkErrorCodes#AI_TOOL_REJECTED}. A refusal is not an error.
     */
    default boolean before(AiToolContext context, AiToolDefinition tool, Object argument) { return true; }

    /**
     * Runs in reverse order after the tool returns (or fails/throws), for every interceptor whose {@link #before}
     * returned {@code true}. For async {@code void} tools that reply later, {@code result} is {@code null}.
     */
    default void after(AiToolContext context, AiToolDefinition tool, Object argument, AiToolResult result, Throwable failure) {}
}
