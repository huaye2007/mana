package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;

import cn.managame.ai.internal.AiToolMetrics;
import cn.managame.ai.internal.ToolMethod;
import cn.managame.ai.spi.AiToolExecutor;
import cn.managame.metrics.Metrics;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Thin, protocol-agnostic registry and invocation boundary for game server methods exposed to external AI services.
 *
 * <p>Tools are declared with {@link cn.managame.ai.annotation.AiTool} and registered once at startup in batches
 * ({@link Builder#register(Collection)}), compiled into {@code MethodHandle}s with no reflection at call time.
 * Mount {@link cn.managame.ai.http.AiToolHttpHandler} on a {@code managame-network} HTTP server, or call
 * {@link #list} and {@link #invoke} directly from an application's own RPC or WebSocket gateway.
 */
public final class AiTools {
    private static final System.Logger LOG = System.getLogger("cn.managame.ai.tool");

    private final Map<String, ToolMethod> tools;
    private final List<AiToolDefinition> definitions;
    private final AiJsonCodec codec;
    private final AiToolExecutor executor;
    private final List<AiToolInterceptor> interceptors;
    private final BiConsumer<AiToolContext, Throwable> errorHandler;
    private final AiToolMetrics metrics;

    private AiTools(Builder b) {
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(b.tools));
        List<AiToolDefinition> defs = new ArrayList<>(this.tools.size());
        for (ToolMethod tm : this.tools.values()) defs.add(tm.definition());
        this.definitions = List.copyOf(defs);
        this.codec = b.codec;
        this.executor = b.executor != null
                ? b.executor
                : AiToolExecutor.of(Executors.newVirtualThreadPerTaskExecutor());
        this.interceptors = List.copyOf(b.interceptors);
        this.errorHandler = b.errorHandler != null ? b.errorHandler : AiTools::defaultErrorHandler;
        this.metrics = new AiToolMetrics(b.metrics, this.tools.keySet());

        LOG.log(Level.INFO, () -> "AiTools built with " + definitions.size() + " tools and "
                + interceptors.size() + " interceptors");
        if (LOG.isLoggable(Level.DEBUG)) {
            for (AiToolDefinition def : definitions) {
                LOG.log(Level.DEBUG, () -> "Registered AI tool " + def.name()
                        + (def.group().isEmpty() ? "" : " [group=" + def.group() + "]")
                        + (def.readOnly() ? " [readOnly]" : ""));
            }
        }
    }

    public static Builder builder() { return new Builder(); }

    /** All registered tool definitions in registration order. */
    public List<AiToolDefinition> definitions() { return definitions; }

    /** Looks up a tool definition by exact name, or {@code null} when absent. */
    public AiToolDefinition find(String name) {
        ToolMethod tm = tools.get(name);
        return tm == null ? null : tm.definition();
    }

    /** Codec configured for encoding tool return values and decoding arguments. */
    public AiJsonCodec codec() { return codec; }

    /**
     * Returns the tools visible to a caller presenting {@code headers}, after filtering through every registered
     * {@link AiToolInterceptor#allowList}.
     */
    public List<AiToolDefinition> list(Map<String, String> headers) {
        if (interceptors.isEmpty()) return definitions;
        Map<String, String> normalized = normalizeHeaders(headers);
        List<AiToolDefinition> visible = new ArrayList<>(definitions.size());
        for (AiToolDefinition def : definitions) {
            if (isAllowedToList(normalized, def)) visible.add(def);
        }
        return List.copyOf(visible);
    }

    /** Convenience overload for in-process invocation without headers or remote address. */
    public void invoke(String name, String argumentsJson, Consumer<AiToolResult> callback) {
        invoke(name, argumentsJson, Map.of(), null, callback);
    }

    /**
     * Decodes {@code argumentsJson}, dispatches the tool through the configured {@link AiToolExecutor}, runs
     * interceptors and the tool method, and delivers the {@link AiToolResult} to {@code callback} exactly once.
     */
    public void invoke(String name, String argumentsJson, Map<String, String> headers,
                       SocketAddress remoteAddress, Consumer<AiToolResult> callback) {
        Objects.requireNonNull(callback, "callback");
        if (name == null || name.isBlank()) {
            metrics.notFound();
            callback.accept(AiToolResult.fail(AI_TOOL_NOT_FOUND, "Tool name must not be blank"));
            return;
        }
        ToolMethod tool = tools.get(name);
        if (tool == null) {
            metrics.notFound();
            LOG.log(Level.DEBUG, () -> "Unknown AI tool requested: " + name);
            callback.accept(AiToolResult.fail(AI_TOOL_NOT_FOUND, "Unknown tool: " + name));
            return;
        }

        Object decodedArg;
        try {
            decodedArg = decodeArgument(tool, argumentsJson);
        } catch (Exception badArgs) {
            metrics.invalidArgs();
            LOG.log(Level.DEBUG, () -> "Invalid arguments for AI tool " + name + ": " + badArgs.getMessage());
            callback.accept(AiToolResult.fail(AI_TOOL_INVALID_ARGS,
                    "Invalid arguments for tool '" + name + "': " + badArgs.getMessage()));
            return;
        }

        long startNanos = metrics.startTimer();
        AiToolMetrics.ToolMeters meters = metrics.forTool(name);
        meters.calls().increment();

        Map<String, String> normalizedHeaders = normalizeHeaders(headers);
        DefaultToolContext context = new DefaultToolContext(
                tool.definition(), normalizedHeaders, remoteAddress, meters, startNanos, callback);
        AiToolCall call = new AiToolCall(tool.definition(), decodedArg, context);

        try {
            executor.execute(call, () -> runTool(tool, context, decodedArg));
        } catch (RuntimeException refused) {
            LOG.log(Level.WARNING, () -> "AiToolExecutor refused tool " + name + ": " + refused.getMessage());
            context.fail(AI_TOOL_REJECTED, refused.getMessage() != null ? refused.getMessage() : "Tool execution rejected");
        }
    }

    private void runTool(ToolMethod tool, DefaultToolContext context, Object argument) {
        AiToolDefinition def = tool.definition();
        int passed = 0;
        Throwable failure = null;
        AiToolResult syncResult = null;
        try {
            for (AiToolInterceptor interceptor : interceptors) {
                if (!interceptor.before(context, def, argument)) {
                    LOG.log(Level.DEBUG, () -> "AI tool " + def.name() + " rejected by interceptor");
                    if (!context.isAnswered()) {
                        context.fail(AI_TOOL_REJECTED, "Tool call rejected");
                    }
                    return;
                }
                passed++;
            }

            Object returned = tool.invoke(context, argument);
            if (returned instanceof AiToolResult toolResult) {
                syncResult = toolResult;
                context.complete(toolResult);
            } else if (returned != null) {
                syncResult = AiToolResult.ok(returned);
                context.complete(syncResult);
            } else if (!tool.returnsVoid()) {
                throw new IllegalStateException("Non-void AI tool '" + def.name() + "' returned null; only void tools answer asynchronously");
            }
        } catch (Throwable t) {
            failure = t;
            notifyErrorHandler(context, t);
            context.fail(AI_TOOL_EXECUTION_ERROR, "Tool execution failed");
        } finally {
            for (int i = passed - 1; i >= 0; i--) {
                try {
                    interceptors.get(i).after(context, def, argument, syncResult, failure);
                } catch (Throwable afterError) {
                    notifyErrorHandler(context, afterError);
                }
            }
        }
    }

    private Object decodeArgument(ToolMethod tool, String argumentsJson) throws Exception {
        if (!tool.hasArgument()) return null;
        String raw = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
        if (tool.isRawStringArgument()) return raw;
        Object decoded = codec.decode(raw, tool.definition().argumentType());
        if (decoded == null) {
            throw new IllegalArgumentException("Decoded argument is null");
        }
        return decoded;
    }

    private boolean isAllowedToList(Map<String, String> headers, AiToolDefinition def) {
        for (AiToolInterceptor interceptor : interceptors) {
            try {
                if (!interceptor.allowList(headers, def)) return false;
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "AiToolInterceptor.allowList threw for tool " + def.name(), t);
                return false;
            }
        }
        return true;
    }

    private void notifyErrorHandler(AiToolContext context, Throwable failure) {
        try {
            errorHandler.accept(context, failure);
        } catch (Throwable secondary) {
            LOG.log(Level.ERROR, "AiTools errorHandler threw", secondary);
        }
    }

    private static void defaultErrorHandler(AiToolContext context, Throwable failure) {
        LOG.log(Level.ERROR, "AI tool '" + context.toolName() + "' failed", failure);
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Map.of();
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        return Collections.unmodifiableMap(copy);
    }

    private final class DefaultToolContext implements AiToolContext {
        private final AiToolDefinition tool;
        private final Map<String, String> headers;
        private final SocketAddress remoteAddress;
        private final AiToolMetrics.ToolMeters meters;
        private final long startNanos;
        private final Consumer<AiToolResult> callback;
        private final AtomicBoolean answered = new AtomicBoolean();

        DefaultToolContext(AiToolDefinition tool, Map<String, String> headers, SocketAddress remoteAddress,
                           AiToolMetrics.ToolMeters meters, long startNanos, Consumer<AiToolResult> callback) {
            this.tool = tool;
            this.headers = headers;
            this.remoteAddress = remoteAddress;
            this.meters = meters;
            this.startNanos = startNanos;
            this.callback = callback;
        }

        @Override public AiToolDefinition tool() { return tool; }
        @Override public Map<String, String> headers() { return headers; }
        @Override public SocketAddress remoteAddress() { return remoteAddress; }
        @Override public boolean isAnswered() { return answered.get(); }

        @Override public boolean complete(AiToolResult result) {
            Objects.requireNonNull(result, "result");
            if (!answered.compareAndSet(false, true)) return false;
            if (!result.isSuccess()) {
                if (result.code() == AI_TOOL_REJECTED) meters.rejected().increment();
                else meters.failures().increment();
            }
            metrics.recordLatency(startNanos);
            try {
                callback.accept(result);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "AI tool response callback threw for " + tool.name(), t);
            }
            return true;
        }
    }

    public static final class Builder {
        private final Map<String, ToolMethod> tools = new LinkedHashMap<>();
        private AiJsonCodec codec = AiJsonCodec.basic();
        private AiToolExecutor executor;
        private final List<AiToolInterceptor> interceptors = new ArrayList<>();
        private BiConsumer<AiToolContext, Throwable> errorHandler;
        private Metrics metrics = Metrics.noop();

        private Builder() {}

        /** Registers every {@link cn.managame.ai.annotation.AiTool} method on each target object. */
        public Builder register(Collection<?> targets) {
            Objects.requireNonNull(targets, "targets");
            for (Object target : targets) {
                for (ToolMethod method : ToolMethod.scan(target)) {
                    addTool(method);
                }
            }
            return this;
        }

        /**
         * Registers a programmatic tool whose {@code handler} receives raw payload text and answers through
         * {@link AiToolContext#reply} or {@link AiToolContext#fail}.
         */
        public Builder register(String name, String description, String group, boolean readOnly,
                                BiConsumer<AiToolContext, String> handler) {
            addTool(ToolMethod.programmatic(name, description, group, readOnly, handler));
            return this;
        }

        /** Codec used to decode tool arguments and encode return values. Defaults to {@link AiJsonCodec#basic()}. */
        public Builder codec(AiJsonCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Scheduler that runs decoded tool invocations (for example routing into {@code GameRuntime}). Defaults to a
         * virtual-thread-per-task executor so Netty EventLoops are never blocked.
         */
        public Builder executor(AiToolExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Registers interceptors run in order around tool discovery and invocation. */
        public Builder intercept(Collection<? extends AiToolInterceptor> interceptors) {
            Objects.requireNonNull(interceptors, "interceptors");
            for (AiToolInterceptor interceptor : interceptors) {
                this.interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            }
            return this;
        }

        /** Receives uncaught exceptions thrown by tools or interceptors. Defaults to logging at ERROR level. */
        public Builder errorHandler(BiConsumer<AiToolContext, Throwable> errorHandler) {
            this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
            return this;
        }

        /** Metrics sink, {@link Metrics#noop()} by default. */
        public Builder metrics(Metrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        public AiTools build() {
            return new AiTools(this);
        }

        private void addTool(ToolMethod method) {
            String name = method.definition().name();
            if (tools.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate AI tool name: " + name);
            }
            tools.put(name, method);
        }
    }
}
