package cn.managame.ai.mcp;

import java.util.Map;

/**
 * Handler for a programmatically registered MCP tool (as an alternative to {@link cn.managame.ai.annotation.McpTool}).
 * Answers through {@code context.reply} or {@code context.fail}, synchronously or later from any thread.
 */
@FunctionalInterface
public interface McpToolHandler {
    void handle(McpToolContext context, Map<String, Object> arguments) throws Exception;
}
