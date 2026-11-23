package cn.managame.ai;

import java.net.SocketAddress;
import java.util.Map;

/**
 * Context of a single AI tool invocation. Provides caller headers, remote address and asynchronous reply methods.
 *
 * <p>Only the first call to {@link #reply}, {@link #fail} or {@link #complete} takes effect; it is safe to call from
 * any thread, including after a {@code void} tool method returns.
 */
public interface AiToolContext {
    /** The registered tool being invoked. */
    AiToolDefinition tool();

    /** Convenience shortcut for {@code tool().name()}. */
    default String toolName() { return tool().name(); }

    /** Immutable case-insensitive map of caller headers (for example HTTP headers). */
    Map<String, String> headers();

    /** Case-insensitive header lookup, or {@code null} when absent. */
    default String header(String name) { return headers().get(name); }

    /** Remote address of the caller, or {@code null} for in-process invocations. */
    SocketAddress remoteAddress();

    /** Answers the invocation successfully with {@code data}. Returns {@code false} if already answered. */
    default boolean reply(Object data) { return complete(AiToolResult.ok(data)); }

    /** Answers the invocation with a positive failure code and message. Returns {@code false} if already answered. */
    default boolean fail(int errorCode, String message) { return complete(AiToolResult.fail(errorCode, message)); }

    /** Answers the invocation with {@code result}. Returns {@code false} if already answered. */
    boolean complete(AiToolResult result);

    /** Whether an answer has already been delivered. */
    boolean isAnswered();
}
