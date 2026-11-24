package cn.managame.ai.mcp;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of one MCP tool invocation handed to {@link McpToolInterceptor} and
 * {@link cn.managame.ai.spi.McpToolExecutor}. Exposes the tool's definition, its declaring Java {@link #method()} (or
 * {@code null} for a programmatically registered tool), the decoded Java arguments, the raw parsed JSON argument map,
 * and the {@link #context()} used to answer or inspect headers.
 */
public final class McpToolCall {
    private final McpToolDefinition definition;
    private final Method method;
    private final Object[] arguments;
    private final Map<String, Object> rawArguments;
    private final McpToolContext context;

    McpToolCall(McpToolDefinition definition, Method method, Object[] arguments,
            Map<String, Object> rawArguments, McpToolContext context) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.method = method;
        this.arguments = arguments;
        this.rawArguments = rawArguments;
        this.context = Objects.requireNonNull(context, "context");
    }

    public McpToolDefinition definition() { return definition; }

    public String toolName() { return definition.name(); }

    /** Annotated Java method backing this tool, or {@code null} if registered programmatically. */
    public Method method() { return method; }

    /** Decoded Java arguments (excluding any leading {@link McpToolContext}); returns a defensive copy. */
    public Object[] arguments() { return arguments.clone(); }

    /** Convenience accessor when the tool takes a single decoded argument (such as a DTO), or {@code null} otherwise. */
    public Object singleArgument() { return arguments.length == 1 ? arguments[0] : null; }

    /** Unmodifiable view of the parsed JSON arguments object from MCP {@code tools/call}. */
    public Map<String, Object> rawArguments() { return rawArguments; }

    public McpToolContext context() { return context; }
}
