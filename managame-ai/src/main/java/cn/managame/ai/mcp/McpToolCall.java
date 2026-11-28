package cn.managame.ai.mcp;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of one MCP tool invocation handed to {@link McpToolInterceptor}, {@link McpToolProxy}, and
 * {@link cn.managame.ai.spi.McpToolExecutor}. Exposes the tool's definition, its declaring Java {@link #method()} (or
 * {@code null} for a programmatically registered tool), the compiled {@link #routeKey()}, the decoded Java arguments,
 * the raw parsed JSON argument map, the verbatim {@link #rawArgumentsJson()} slice, and the {@link #context()} used to
 * answer or inspect headers and metadata.
 */
public final class McpToolCall {
    private final McpToolDefinition definition;
    private final Method method;
    private final Object[] arguments;
    private final Map<String, Object> rawArguments;
    private Map<String, Object> unmodifiableRaw;
    private final String rawArgumentsJson;
    private final McpToolContext context;
    private final boolean hasRouteKey;
    private final long routeKey;

    McpToolCall(McpToolDefinition definition, Method method, Object[] arguments,
            Map<String, Object> rawArguments, String rawArgumentsJson, McpToolContext context,
            boolean hasRouteKey, long routeKey) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.method = method;
        this.arguments = arguments;
        this.rawArguments = rawArguments;
        this.rawArgumentsJson = Objects.requireNonNull(rawArgumentsJson, "rawArgumentsJson");
        this.context = Objects.requireNonNull(context, "context");
        this.hasRouteKey = hasRouteKey;
        this.routeKey = routeKey;
    }

    public McpToolDefinition definition() { return definition; }

    public String toolName() { return definition.name(); }

    /** Annotated Java method backing this tool, or {@code null} if registered programmatically. */
    public Method method() { return method; }

    /** Whether the tool names its route key with {@link cn.managame.ai.annotation.McpTool#routeKey()}. */
    public boolean hasRouteKey() { return hasRouteKey; }

    /**
     * The route key read from this call's arguments by {@link cn.managame.ai.annotation.McpTool#routeKey()}, for an
     * executor that runs tools on routes; {@code 0} when the tool names none ({@link #hasRouteKey()} false).
     */
    public long routeKey() { return routeKey; }

    /** Decoded Java arguments (excluding any leading {@link McpToolContext}); returns a defensive copy. */
    public Object[] arguments() { return arguments.clone(); }

    /** Convenience accessor when the tool takes a single decoded argument (such as a DTO), or {@code null} otherwise. */
    public Object singleArgument() { return arguments.length == 1 ? arguments[0] : null; }

    /** Unmodifiable view of the parsed JSON arguments object from MCP {@code tools/call}. */
    public Map<String, Object> rawArguments() {
        if (rawArguments.isEmpty()) return Map.of();
        Map<String, Object> view = unmodifiableRaw;
        if (view == null) {
            view = Collections.unmodifiableMap(rawArguments);
            unmodifiableRaw = view;
        }
        return view;
    }

    /**
     * Raw JSON object string of the MCP {@code arguments} payload (never null, {@code "{}"} when empty), suitable for
     * audit logging or zero-reserialization RPC forwarding in {@link McpToolProxy}.
     */
    public String rawArgumentsJson() { return rawArgumentsJson; }

    public McpToolContext context() { return context; }
}
