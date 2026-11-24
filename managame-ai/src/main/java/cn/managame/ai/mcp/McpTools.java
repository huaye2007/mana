package cn.managame.ai.mcp;

import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;

import cn.managame.ai.AiCodec;
import cn.managame.ai.internal.BasicAiCodec;
import cn.managame.ai.internal.Json;
import cn.managame.ai.internal.McpMetrics;
import cn.managame.ai.internal.ToolMethod;
import cn.managame.ai.spi.McpToolExecutor;
import cn.managame.metrics.Metrics;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registry and dispatcher of MCP tools exposed by a game server. Scans objects for
 * {@link cn.managame.ai.annotation.McpTool} methods (compiling each once into a {@code MethodHandle} and JSON Schema),
 * applies {@link McpToolInterceptor}s for discovery filtering, authorization and auditing, protects the server with
 * {@code maxInFlight} admission and optional per-call {@code timeout}, and runs calls on the configured
 * {@link McpToolExecutor}:
 *
 * <pre>{@code
 * McpTools tools = McpTools.builder()
 *         .codec(jsonCodec)
 *         .executor(gameRuntimeExecutor)
 *         .intercept(List.of(authInterceptor))
 *         .maxInFlight(256)
 *         .timeout(Duration.ofSeconds(10))
 *         .register(List.of(playerAdmin, gmService))
 *         .metrics(metrics)
 *         .build();
 *
 * NetworkServer.start(8082,
 *         HttpInitializer.http().contextPath("/mcp").handler(new McpHttpHandler(tools)).build());
 * }</pre>
 *
 * <p>Registrations are immutable once built. Can be served over MCP Streamable HTTP via {@link McpHttpHandler}, or
 * invoked directly from tests or custom transports (such as {@code managame-rpc}).
 */
public final class McpTools implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("cn.managame.ai.mcp");
    private static final int DEFAULT_MAX_IN_FLIGHT = 256;
    private static final Object[] EMPTY_ARGS = {};
    private static final Map<String, String> EMPTY_HEADERS = Map.of();
    private static final int QUEUED = 0, STARTED = 1, TIMED_OUT = 2;

    private final Map<String, RegisteredTool> tools;
    private final List<McpToolDefinition> definitions;
    private final AiCodec codec;
    private final McpToolExecutor executor;
    private final List<McpToolInterceptor> interceptors;
    private final int maxInFlight;
    private final Duration timeout;
    private final Function<Object, McpToolResult> resultAdapter;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final McpMetrics metrics;
    private final AtomicReference<ScheduledExecutorService> timeoutScheduler = new AtomicReference<>();
    private volatile boolean closed;

    private McpTools(Builder b) {
        this.codec = b.codec;
        this.executor = b.executor;
        this.interceptors = List.copyOf(b.interceptors);
        this.maxInFlight = b.maxInFlight;
        this.timeout = b.timeout;
        this.resultAdapter = b.resultAdapter;
        this.metrics = new McpMetrics(b.metrics, inFlight::get);
        Map<String, RegisteredTool> registered = new LinkedHashMap<>(b.entries.size());
        List<McpToolDefinition> defs = new ArrayList<>(b.entries.size());
        for (Builder.Entry entry : b.entries.values()) {
            McpToolDefinition def = entry.definition();
            McpMetrics.ToolMeters meters = metrics.forTool(def.name());
            registered.put(def.name(), new RegisteredTool(def, entry.method, entry.handler, meters));
            defs.add(def);
        }
        this.tools = Collections.unmodifiableMap(registered);
        this.definitions = List.copyOf(defs);
        LOG.log(Level.INFO, () -> "McpTools ready with " + definitions.size() + " tools (maxInFlight=" + maxInFlight + ")");
        for (RegisteredTool tool : registered.values()) {
            LOG.log(Level.DEBUG, () -> "MCP tool '" + tool.definition.name() + "' (group=" + tool.definition.group()
                    + ", readOnly=" + tool.definition.readOnly() + ", idempotent=" + tool.definition.idempotent() + ")"
                    + (tool.method != null ? " -> " + tool.method : " (handler)"));
        }
    }

    public static Builder builder() { return new Builder(); }

    /** All registered tool definitions, in registration order. */
    public List<McpToolDefinition> definitions() { return definitions; }

    /** The codec used for argument decoding and result encoding. */
    public AiCodec codec() { return codec; }

    /** Current number of in-flight tool invocations. */
    public int inFlight() { return inFlight.get(); }

    public boolean isClosed() { return closed; }

    /** Tools visible to a caller with {@code headers}, after filtering through {@link McpToolInterceptor#allowList}. */
    public List<McpToolDefinition> list(Map<String, String> headers) {
        if (interceptors.isEmpty()) return definitions;
        Map<String, String> safeHeaders = headers == null || headers.isEmpty() ? EMPTY_HEADERS : caseInsensitive(headers);
        return listNormalized(safeHeaders);
    }

    /** Package-private fast path for {@link McpHttpHandler}, whose headers are already case-insensitive and immutable. */
    List<McpToolDefinition> listNormalized(Map<String, String> safeHeaders) {
        if (interceptors.isEmpty()) return definitions;
        List<McpToolDefinition> visible = new ArrayList<>(definitions.size());
        for (McpToolDefinition def : definitions) {
            if (isAllowedToList(safeHeaders, def)) visible.add(def);
        }
        return List.copyOf(visible);
    }

    /** Invokes tool {@code name} with raw JSON object {@code argumentsJson} and no headers. */
    public void call(String name, String argumentsJson, Consumer<McpToolResult> callback) {
        call(name, argumentsJson, EMPTY_HEADERS, null, callback);
    }

    /** Invokes tool {@code name} with raw JSON object {@code argumentsJson}, caller {@code headers} and peer address. */
    public void call(String name, String argumentsJson, Map<String, String> headers,
            SocketAddress remoteAddress, Consumer<McpToolResult> callback) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(callback, "callback");
        if (closed) {
            LOG.log(Level.WARNING, () -> "MCP tool '" + name + "' refused (closed)");
            deliver(callback, McpToolResult.fail(AI_CLOSED, "McpTools is closed"));
            return;
        }
        Map<String, Object> rawArgs;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            rawArgs = Map.of();
        } else {
            try {
                rawArgs = Json.parseObject(argumentsJson);
            } catch (IllegalArgumentException badJson) {
                metrics.invalidArgs();
                LOG.log(Level.WARNING, () -> "Invalid JSON arguments for MCP tool '" + name + "': " + badJson.getMessage());
                deliver(callback, McpToolResult.fail(AI_TOOL_INVALID_ARGS, "Invalid arguments JSON: " + badJson.getMessage()));
                return;
            }
        }
        Map<String, String> safeHeaders = headers == null || headers.isEmpty() ? EMPTY_HEADERS : caseInsensitive(headers);
        invokeParsed(name, rawArgs, argumentsJson, safeHeaders, remoteAddress, callback);
    }

    /** Package-private fast path for {@link McpHttpHandler}, avoiding redundant JSON parsing of {@code arguments}. */
    void invokeParsed(String name, Map<String, Object> rawArgs, String rawJson,
            Map<String, String> headers, SocketAddress remoteAddress, Consumer<McpToolResult> callback) {
        if (closed) {
            LOG.log(Level.WARNING, () -> "MCP tool '" + name + "' refused (closed)");
            deliver(callback, McpToolResult.fail(AI_CLOSED, "McpTools is closed"));
            return;
        }
        RegisteredTool tool = tools.get(name);
        if (tool == null) {
            metrics.notFound();
            LOG.log(Level.WARNING, () -> "Unknown MCP tool '" + name + "'");
            deliver(callback, McpToolResult.fail(AI_TOOL_NOT_FOUND, "Unknown tool: " + name));
            return;
        }
        if (!tryAcquireSlot()) {
            metrics.overloaded();
            LOG.log(Level.WARNING, () -> "MCP tools overloaded (" + maxInFlight + " in flight) for '" + name + "'");
            deliver(callback, McpToolResult.fail(AI_OVERLOADED, "Max in-flight MCP tool calls reached (" + maxInFlight + ")"));
            return;
        }
        tool.meters.calls().increment();
        Map<String, Object> unmodifiableRaw = rawArgs.isEmpty() ? Map.of() : Collections.unmodifiableMap(rawArgs);
        Object[] decodedArgs;
        boolean hasRouteKey = tool.method != null && tool.method.hasRouteKey();
        long routeKey = 0;
        if (tool.method != null) {
            try {
                decodedArgs = tool.method.decodeArguments(unmodifiableRaw, rawJson, codec);
                if (hasRouteKey) routeKey = tool.method.routeKey(decodedArgs);
            } catch (Throwable cause) {
                inFlight.decrementAndGet();
                metrics.invalidArgs();
                tool.meters.failures().increment();
                LOG.log(Level.WARNING, () -> "Invalid arguments for MCP tool '" + name + "': " + describeError(cause));
                deliver(callback, McpToolResult.fail(AI_TOOL_INVALID_ARGS, describeError(cause)));
                return;
            }
        } else {
            decodedArgs = EMPTY_ARGS;
        }

        long startNanos = metrics.startTimer();
        AtomicInteger entered = new AtomicInteger();
        McpToolCall[] callHolder = new McpToolCall[1];
        AtomicReference<ScheduledFuture<?>> timerRef = timeout != null ? new AtomicReference<>() : null;
        // QUEUED -> STARTED when the tool begins, or QUEUED -> TIMED_OUT when its timeout comes first, never both
        AtomicInteger stage = timeout != null ? new AtomicInteger(QUEUED) : null;
        McpToolContext context = new McpToolContext(name, headers, remoteAddress, resultAdapter, result -> {
            inFlight.decrementAndGet();
            if (timerRef != null) {
                ScheduledFuture<?> f = timerRef.getAndSet(null);
                if (f != null) f.cancel(false);
            }
            metrics.recordLatency(startNanos);
            if (!result.isSuccess()) {
                if (result.code() == AI_TOOL_REJECTED) tool.meters.rejected().increment();
                else tool.meters.failures().increment();
            }
            McpToolCall completedCall = callHolder[0];
            for (int i = entered.get() - 1; i >= 0; i--) {
                try {
                    interceptors.get(i).after(completedCall, result);
                } catch (Throwable cause) {
                    LOG.log(Level.ERROR, "Uncaught exception in McpToolInterceptor.after for '" + name + "'", cause);
                }
            }
            deliver(callback, result);
        });
        McpToolCall call = new McpToolCall(
                tool.definition,
                tool.method != null ? tool.method.method() : null,
                decodedArgs,
                unmodifiableRaw,
                context,
                hasRouteKey,
                routeKey);
        callHolder[0] = call;

        if (closed) {
            context.fail(AI_CLOSED, "McpTools is closed");
            return;
        }
        if (timerRef != null) {
            try {
                ScheduledFuture<?> future = scheduler().schedule(() -> {
                    String message = timeoutMessage(tool.definition, stage.compareAndSet(QUEUED, TIMED_OUT));
                    if (context.fail(AI_TIMEOUT, message)) {
                        LOG.log(Level.WARNING, () -> message);
                    }
                }, timeout.toNanos(), TimeUnit.NANOSECONDS);
                timerRef.set(future);
                if (context.isAnswered()) future.cancel(false);
            } catch (RejectedExecutionException closedRace) {
                context.fail(AI_CLOSED, "McpTools is closed");
                return;
            }
        }

        Runnable task = () -> runTool(tool, call, decodedArgs, entered, stage);
        try {
            executor.execute(call, task);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "McpToolExecutor rejected MCP tool '" + name + "'", cause);
            context.fail(AI_TOOL_EXECUTION_ERROR, describeError(cause));
        }
    }

    /**
     * Marks this registry closed (subsequent calls fail with {@link cn.managame.FrameworkErrorCodes#AI_CLOSED}), shuts
     * down the internal tool-timeout scheduler if started, and closes owned metric meters. Safe to call more than once.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        ScheduledExecutorService sched = timeoutScheduler.getAndSet(null);
        if (sched != null) sched.shutdownNow();
        metrics.close();
    }

    private boolean tryAcquireSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) return false;
            if (inFlight.compareAndSet(current, current + 1)) return true;
        }
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService existing = timeoutScheduler.get();
        if (existing != null) return existing;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "managame-mcp-timeout");
            t.setDaemon(true);
            return t;
        });
        if (timeoutScheduler.compareAndSet(null, created)) {
            if (closed) created.shutdownNow();
            return created;
        }
        created.shutdownNow();
        return timeoutScheduler.get();
    }

    private void runTool(RegisteredTool tool, McpToolCall call, Object[] decodedArgs, AtomicInteger entered,
            AtomicInteger stage) {
        McpToolContext context = call.context();
        // Skip execution if its timeout came first, or something else answered while it waited in a queue
        if (stage != null && !stage.compareAndSet(QUEUED, STARTED)) return;
        if (context.isAnswered()) return;
        try {
            for (int i = 0; i < interceptors.size(); i++) {
                boolean allowed = interceptors.get(i).before(call);
                if (!allowed) {
                    LOG.log(Level.WARNING, () -> "MCP tool '" + tool.definition.name() + "' rejected by interceptor");
                    if (!context.isAnswered()) context.fail(AI_TOOL_REJECTED, "Tool call rejected");
                    return;
                }
                entered.set(i + 1);
            }
            if (context.isAnswered()) return;
            if (tool.method != null) {
                Object result = tool.method.invoke(context, decodedArgs);
                if (tool.method.isAsync()) return;
                if (tool.method.returnsVoid()) {
                    context.reply("ok");
                } else if (result == null) {
                    if (!context.isAnswered()) {
                        IllegalStateException bug = new IllegalStateException(
                                tool.method + " returned null; only a void tool with McpToolContext answers later");
                        LOG.log(Level.ERROR, "MCP tool '" + tool.definition.name() + "' returned null", bug);
                        context.fail(AI_TOOL_EXECUTION_ERROR, bug.getMessage());
                    }
                } else {
                    context.reply(result);
                }
            } else {
                tool.handler.handle(context, call.rawArguments());
            }
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "MCP tool '" + tool.definition.name() + "' threw an exception", cause);
            context.fail(AI_TOOL_EXECUTION_ERROR, describeError(cause));
        }
    }

    private boolean isAllowedToList(Map<String, String> headers, McpToolDefinition def) {
        for (McpToolInterceptor interceptor : interceptors) {
            try {
                if (!interceptor.allowList(headers, def)) return false;
            } catch (Throwable cause) {
                LOG.log(Level.ERROR, "McpToolInterceptor.allowList threw for '" + def.name() + "'", cause);
                return false;
            }
        }
        return true;
    }

    static Map<String, String> caseInsensitive(Map<String, String> source) {
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }

    private static void deliver(Consumer<McpToolResult> callback, McpToolResult result) {
        try {
            callback.accept(result);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "Uncaught exception in MCP tool completion callback", cause);
        }
    }

    private static String describeError(Throwable cause) {
        String msg = cause.getMessage();
        return msg != null && !msg.isBlank() ? msg : cause.getClass().getSimpleName();
    }

    /**
     * What a timeout tells the model: a tool that had not started will not run; a mutating tool that is not idempotent
     * and had started may still take effect, so the model checks before calling it again rather than repeating it.
     */
    private String timeoutMessage(McpToolDefinition def, boolean beforeStart) {
        String head = "MCP tool '" + def.name() + "' timed out after " + timeout;
        if (beforeStart) return head + " before it started; it will not run";
        if (def.readOnly() || def.idempotent()) return head;
        return head + "; it may still take effect, so check its result before calling it again";
    }

    private record RegisteredTool(McpToolDefinition definition, ToolMethod method,
            McpToolHandler handler, McpMetrics.ToolMeters meters) {}

    public static final class Builder {
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final List<McpToolInterceptor> interceptors = new ArrayList<>();
        private AiCodec codec = AiCodec.basic();
        private McpToolExecutor executor = McpToolExecutor.virtualThreads();
        private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;
        private Duration timeout;
        private Function<Object, McpToolResult> resultAdapter;
        private Metrics metrics = Metrics.noop();

        private Builder() {}

        /** Registers every {@link cn.managame.ai.annotation.McpTool} method of each target object. */
        public Builder register(Collection<?> targets) {
            Objects.requireNonNull(targets, "targets");
            for (Object target : targets) {
                for (ToolMethod method : ToolMethod.scan(target)) {
                    String name = method.definition().name();
                    if (entries.putIfAbsent(name, new Entry(method, null, null)) != null) {
                        throw new IllegalArgumentException("Duplicate MCP tool name '" + name + "'");
                    }
                }
            }
            return this;
        }

        /** Registers a programmatic MCP tool with an explicit {@link McpToolDefinition} and {@link McpToolHandler}. */
        public Builder register(McpToolDefinition definition, McpToolHandler handler) {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(handler, "handler");
            // Validate that inputSchema is a JSON object
            Json.parseObject(definition.inputSchema());
            if (entries.putIfAbsent(definition.name(), new Entry(null, definition, handler)) != null) {
                throw new IllegalArgumentException("Duplicate MCP tool name '" + definition.name() + "'");
            }
            return this;
        }

        /** Adds interceptors in execution order for tool discovery and invocation. */
        public Builder intercept(Collection<? extends McpToolInterceptor> interceptors) {
            Objects.requireNonNull(interceptors, "interceptors");
            for (McpToolInterceptor interceptor : interceptors) {
                this.interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            }
            return this;
        }

        /** Codec for decoding tool DTO arguments and encoding tool results; {@link AiCodec#basic()} by default. */
        public Builder codec(AiCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /** Executor dispatching tool calls; {@link McpToolExecutor#virtualThreads()} by default. */
        public Builder executor(McpToolExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Maximum concurrent in-flight tool calls before failing with AI_OVERLOADED; 256 by default. */
        public Builder maxInFlight(int maxInFlight) {
            if (maxInFlight <= 0) throw new IllegalArgumentException("maxInFlight must be positive");
            this.maxInFlight = maxInFlight;
            return this;
        }

        /**
         * Optional safety timeout per tool invocation: if a tool (for example an asynchronous {@code void} tool or a
         * queued task) does not answer within {@code timeout}, it is failed with
         * {@link cn.managame.FrameworkErrorCodes#AI_TIMEOUT} and skipped if it has not started yet. Disabled by default.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
            this.timeout = timeout;
            return this;
        }

        /**
         * Optional adapter translating application result types (for example {@code HttpResult} or {@code HandleResult}
         * when reusing existing handler methods) into {@link McpToolResult}. Returning {@code null} leaves the value as
         * a normal successful payload.
         */
        public Builder resultAdapter(Function<Object, McpToolResult> resultAdapter) {
            this.resultAdapter = Objects.requireNonNull(resultAdapter, "resultAdapter");
            return this;
        }

        /** Where metrics are reported; {@link Metrics#noop()} by default. */
        public Builder metrics(Metrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        /** Validates registrations (including codec compatibility under {@link AiCodec#basic()}) and builds. */
        public McpTools build() {
            if (codec == BasicAiCodec.INSTANCE) {
                for (Entry entry : entries.values()) {
                    if (entry.method != null) entry.method.verifyBasicCodec();
                }
            }
            return new McpTools(this);
        }

        private record Entry(ToolMethod method, McpToolDefinition explicitDef, McpToolHandler handler) {
            McpToolDefinition definition() { return method != null ? method.definition() : explicitDef; }
        }
    }
}
