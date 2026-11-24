package cn.managame.ai.spi;

import cn.managame.ai.mcp.McpToolCall;

/**
 * Dispatches an {@link cn.managame.ai.mcp.McpTools} invocation onto the application's execution model. By default tools
 * run on virtual threads ({@link #virtualThreads()}); an application on {@code managame-runtime} supplies an executor
 * that inspects {@code call.method()} and {@code call.rawArguments()} (or {@code call.singleArgument()}) and submits
 * {@code task} to {@code GameRuntime.execute}, calling {@code call.context().fail(...)} if the runtime refuses it.
 */
@FunctionalInterface
public interface McpToolExecutor {
    /** Runs {@code task} for {@code call} without blocking the caller's EventLoop. */
    void execute(McpToolCall call, Runnable task);

    /** Runs each tool call on its own daemon virtual thread. */
    static McpToolExecutor virtualThreads() {
        return (call, task) -> Thread.ofVirtual().name("managame-mcp-tool-", 0).start(task);
    }

    /** Runs the tool inline on the calling thread; intended for tests and benchmarks, never on a Netty EventLoop. */
    static McpToolExecutor direct() {
        return (call, task) -> task.run();
    }
}
