package cn.managame.ai.mcp;

import cn.managame.FrameworkErrorCodes;
import java.util.Objects;

/**
 * Outcome of an MCP tool invocation: returned directly by a synchronous {@link cn.managame.ai.annotation.McpTool}
 * method when it wants to report a domain failure code, and delivered to the caller's completion callback.
 * {@code code} is {@link FrameworkErrorCodes#SUCCESS} on success or a positive failure code (framework {@code 5007..5010}
 * or application-defined).
 */
public record McpToolResult(int code, Object data, String message) {
    public McpToolResult {
        Objects.requireNonNull(message, "message");
    }

    public boolean isSuccess() { return code == FrameworkErrorCodes.SUCCESS; }

    /** A successful tool answer with {@code data} ({@code null} renders as {@code "ok"}). */
    public static McpToolResult ok(Object data) {
        return new McpToolResult(FrameworkErrorCodes.SUCCESS, data, "");
    }

    /** A failed tool answer; rendered with MCP {@code isError: true} and {@code message}. */
    public static McpToolResult fail(int code, String message) {
        if (code == FrameworkErrorCodes.SUCCESS) throw new IllegalArgumentException("Failure code must not be SUCCESS");
        return new McpToolResult(code, null, message == null ? "" : message);
    }
}
