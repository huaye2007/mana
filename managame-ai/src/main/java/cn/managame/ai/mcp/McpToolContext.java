package cn.managame.ai.mcp;

import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Context of one MCP tool invocation. Provides caller HTTP headers (case-insensitive lookup), peer address, typed
 * {@link Metadata} shared across interceptors and the tool itself via {@link #put}, and at-most-once {@link #reply} /
 * {@link #fail} methods safe to call from any thread (for example from an RPC or runtime callback in an asynchronous
 * {@code void} tool).
 */
public final class McpToolContext {
    private final String toolName;
    private volatile Metadata metadata = Metadata.empty();
    private final Map<String, String> headers;
    private final SocketAddress remoteAddress;
    private final Function<Object, McpToolResult> resultAdapter;
    private final Predicate<McpToolResult> sink;
    private volatile boolean answered;

    McpToolContext(String toolName, Map<String, String> headers, SocketAddress remoteAddress,
            Function<Object, McpToolResult> resultAdapter, Predicate<McpToolResult> sink) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.remoteAddress = remoteAddress;
        this.resultAdapter = resultAdapter;
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public String toolName() { return toolName; }

    /** Application-defined metadata attached to this tool call via {@link #put}. */
    public Metadata metadata() { return metadata; }

    /** Sets {@code key} to {@code value} in this context's {@link Metadata} (replacing any previous value). */
    public <T> void put(MetadataKey<T> key, T value) {
        metadata = metadata.with(key, value);
    }

    /** Immutable, case-insensitive view of the request's headers (for example {@code Authorization}, {@code X-Trace-Id}). */
    public Map<String, String> headers() { return headers; }

    /** Convenience lookup for a request header (case-insensitive), or {@code null} when absent. */
    public String header(String name) { return headers.get(name); }

    /** Peer address of the incoming connection, or {@code null} when invoked directly outside HTTP. */
    public SocketAddress remoteAddress() { return remoteAddress; }

    /** Answers the tool call with {@code data}; returns {@code false} if already answered. */
    public boolean reply(Object data) {
        if (data instanceof McpToolResult result) return complete(result);
        if (data != null && resultAdapter != null) {
            try {
                McpToolResult adapted = resultAdapter.apply(data);
                if (adapted != null) return complete(adapted);
            } catch (Throwable cause) {
                String msg = cause.getMessage();
                return fail(AI_TOOL_EXECUTION_ERROR, msg != null && !msg.isBlank() ? msg : cause.getClass().getSimpleName());
            }
        }
        return complete(McpToolResult.ok(data));
    }

    /** Answers the tool call with {@code errorCode}; returns {@code false} if already answered. */
    public boolean fail(int errorCode) {
        return fail(errorCode, "");
    }

    /** Answers the tool call with {@code errorCode} and {@code message}; returns {@code false} if already answered. */
    public boolean fail(int errorCode, String message) {
        return complete(McpToolResult.fail(errorCode, message));
    }

    /** Whether {@link #reply} or {@link #fail} has already answered this call. */
    public boolean isAnswered() { return answered; }

    boolean complete(McpToolResult result) {
        Objects.requireNonNull(result, "result");
        if (answered) return false;
        boolean won = sink.test(result);
        if (won) answered = true;
        return won;
    }

    void markAnswered() {
        answered = true;
    }
}
