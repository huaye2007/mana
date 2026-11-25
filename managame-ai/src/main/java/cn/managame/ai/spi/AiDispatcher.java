package cn.managame.ai.spi;

import cn.managame.Metadata;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Bridges {@link cn.managame.ai.AiClient} onto a game server's execution and context model (such as
 * {@code managame-runtime}, an actor system, or a custom thread-per-player framework) without coupling
 * {@code managame-ai} to any specific runtime:
 *
 * <pre>{@code
 * // Configured once when building the server's single AiClient:
 * AiDispatcher runtimeDispatcher = new AiDispatcher() {
 *     public Executor capture(Object context) {
 *         if (context != null) {
 *             Context target = (Context) context;
 *             runtime.checkDomain(target.domain());
 *             return task -> runtime.handOn(target, _ -> task.run());
 *         }
 *         Continuation back = runtime.continuation();   // enforces Context.current() & captures player route
 *         return task -> back.execute(_ -> task.run());
 *     }
 *     public Metadata metadata(Object context) {
 *         if (context instanceof Context target) return target.metadata();
 *         return Context.hasCurrent() ? Context.current().metadata() : Metadata.empty();
 *     }
 * };
 * }</pre>
 *
 * <p>With a dispatcher configured once on {@link cn.managame.ai.AiClient}, business modules across the server simply
 * call {@code aiClient.call(...)} or {@code aiClient.stream(...)}: context validation, {@link Metadata} header
 * propagation, and resuming callbacks on the caller's route happen automatically.
 */
@FunctionalInterface
public interface AiDispatcher {
    /**
     * Captures the route / context continuation on the calling thread when an {@code AiClient.call} or
     * {@code AiClient.stream} is initiated. {@code context} is {@link cn.managame.ai.AiRequest#context()} ({@code null}
     * unless explicitly set via {@code AiRequest.withContext(...)} for an external entry). Throw
     * {@link IllegalStateException} when the server framework requires an active context and none is available.
     */
    Executor capture(Object context);

    /**
     * Ambient {@link Metadata} of the current thread (when {@code context} is {@code null}) or of the explicit
     * {@code context}, propagated automatically as HTTP headers before per-request metadata. Empty by default.
     */
    default Metadata metadata(Object context) {
        return Metadata.empty();
    }

    /**
     * Default standalone dispatcher when no server runtime is wired: executes callbacks on daemon virtual threads
     * ({@code AiClient} guarantees sequential delivery per stream on top of the returned executor).
     */
    static AiDispatcher virtualThreads() {
        Executor vthread = task -> Thread.ofVirtual().name("managame-ai-cb-", 0).start(task);
        return context -> vthread;
    }

    /** Wraps a plain stateless {@link Executor} for every call and stream. */
    static AiDispatcher ofExecutor(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return context -> executor;
    }
}
