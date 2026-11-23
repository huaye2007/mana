package cn.managame.ai.spi;

import cn.managame.ai.AiToolCall;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Schedules how each decoded {@link AiToolCall} runs.
 *
 * <p>Implementations must not block a Netty EventLoop thread. Applications using {@code managame-runtime} typically
 * inspect {@code call.method()} and {@code call.argument()} to route the invocation onto the target
 * {@code (domain, routeKey)} via {@code GameRuntime.execute}.
 */
@FunctionalInterface
public interface AiToolExecutor {
    /**
     * Schedules {@code invocation} to run on the appropriate thread or route. If the executor refuses the call, it may
     * fail {@code call.context()} itself or throw a {@link RuntimeException} (which causes {@code AiTools} to answer
     * with {@link cn.managame.FrameworkErrorCodes#AI_TOOL_REJECTED}).
     */
    void execute(AiToolCall call, Runnable invocation);

    /** Runs invocations immediately on the calling thread (for tests or synchronous local callers). */
    static AiToolExecutor direct() { return (call, invocation) -> invocation.run(); }

    /** Adapts any standard {@link Executor} such as a virtual-thread executor or thread pool. */
    static AiToolExecutor of(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return (call, invocation) -> executor.execute(invocation);
    }
}
