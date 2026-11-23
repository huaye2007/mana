package cn.managame.ai;

import java.lang.reflect.Method;

/**
 * One decoded tool invocation handed to {@link cn.managame.ai.spi.AiToolExecutor} for scheduling.
 *
 * @param tool     the registered tool definition
 * @param argument the decoded argument object, or {@code null} for zero-argument tools
 * @param context  the invocation context used to inspect headers or answer asynchronously
 */
public record AiToolCall(AiToolDefinition tool, Object argument, AiToolContext context) {
    /** Underlying declaring Java method, or {@code null} for programmatic tools. */
    public Method method() { return tool.method(); }
}
