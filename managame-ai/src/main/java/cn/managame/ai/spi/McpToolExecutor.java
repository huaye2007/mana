package cn.managame.ai.spi;

import cn.managame.ai.mcp.McpToolCall;
import cn.managame.ai.mcp.McpToolDefinition;
import java.lang.reflect.Method;

/**
 * Dispatches {@link cn.managame.ai.mcp.McpTools} invocations onto a game server's execution model (such as
 * {@code managame-runtime}, an actor framework, or virtual threads) without coupling {@code managame-ai} to a specific
 * runtime.
 *
 * <p>{@link #prepare} is called once per registered tool during {@link cn.managame.ai.mcp.McpTools.Builder#build()} so
 * an integration can validate framework annotations (for example {@code @Domain}) and pre-build per-tool dispatch
 * metadata at startup with zero reflection on the hot path. {@link #execute} runs the tool invocation, and
 * {@link #complete} runs post-completion interceptors and result encoding when an asynchronous {@code void} tool
 * answers from an external I/O thread (such as an RPC EventLoop) so I/O threads never run business or codec work.
 */
@FunctionalInterface
public interface McpToolExecutor {
    /**
     * Called once per registered tool at {@code McpTools.Builder.build()} time ({@code method} is {@code null} for
     * programmatic tools). Throw {@link IllegalArgumentException} to reject invalid tool configurations at startup.
     */
    default void prepare(McpToolDefinition definition, Method method) {}

    /** Dispatches {@code task} for {@code call} without blocking the caller's HTTP EventLoop. */
    void execute(McpToolCall call, Runnable task);

    /**
     * Dispatches post-completion work ({@code McpToolInterceptor.after} and response encoding) when an asynchronous
     * tool calls {@code McpToolContext.reply} / {@code fail} from a thread other than the one running {@code task}
     * (for example from an RPC callback on a Netty EventLoop). Defaults to a daemon virtual thread; an integration with
     * a route-based runtime can hand the completion back onto the tool's route.
     */
    default void complete(McpToolCall call, Runnable completion) {
        Thread.ofVirtual().name("managame-mcp-cb-", 0).start(completion);
    }

    /** Runs each tool call on its own daemon virtual thread. */
    static McpToolExecutor virtualThreads() {
        return (call, task) -> Thread.ofVirtual().name("managame-mcp-tool-", 0).start(task);
    }

    /**
     * Runs both tool execution and async completion inline on the calling thread; intended for unit tests and
     * microbenchmarks, never on a Netty EventLoop.
     */
    static McpToolExecutor direct() {
        return new McpToolExecutor() {
            @Override public void execute(McpToolCall call, Runnable task) { task.run(); }
            @Override public void complete(McpToolCall call, Runnable completion) { completion.run(); }
        };
    }
}
