package cn.managame.ai.mcp;

import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;
import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_CLOSED;
import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;

import cn.managame.ai.AiCodec;
import cn.managame.ai.internal.AiTimer;
import cn.managame.ai.internal.BasicAiCodec;
import cn.managame.ai.internal.Json;
import cn.managame.ai.internal.McpMetrics;
import cn.managame.ai.internal.ToolMethod;
import cn.managame.ai.spi.McpToolExecutor;
import cn.managame.metrics.Metrics;
import io.netty.util.Timeout;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registry and dispatcher of MCP tools exposed by a game server. Scans objects for
 * {@link cn.managame.ai.annotation.McpTool} methods (compiling each once into a {@code MethodHandle}, route-key
 * extractor, and JSON Schema), calls {@link McpToolExecutor#prepare} once per tool at startup, applies
 * {@link McpToolInterceptor}s for discovery filtering, authorization and auditing, protects the server with
 * {@code maxInFlight} admission and optional per-call {@code timeout}, and runs calls on the configured
 * {@link McpToolExecutor}:
 *
 * <pre>{@code
 * McpTools tools = McpTools.builder()
 *         .codec(jsonCodec)
 *         .executor(gameRuntimeExecutor)
 *         .intercept(List.of(authInterceptor))
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
    private static final int DEFAULT_MAX_IN_FLIGHT = 1024;
    private static final int FIRST_FRAMEWORK_CODE = 2001, LAST_FRAMEWORK_CODE = 5999;
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
    private final Set<Invocation> active = ConcurrentHashMap.newKeySet();
    private final McpMetrics metrics;
    private volatile boolean closed;

    private McpTools(Builder b) {
        this.codec = b.codec;
        this.executor = b.executor;
        this.interceptors = List.copyOf(b.interceptors);
        this.maxInFlight = b.maxInFlight;
        this.timeout = b.timeout;
        this.resultAdapter = b.resultAdapter;
        this.metrics = new McpMetrics(b.metrics, b.metricsName, inFlight::get);
        Map<String, RegisteredTool> registered = new LinkedHashMap<>(b.entries.size());
        List<McpToolDefinition> defs = new ArrayList<>(b.entries.size());
        for (Builder.Entry entry : b.entries.values()) {
            McpToolDefinition def = entry.definition();
            McpMetrics.ToolMeters meters = metrics.forTool(def.name());
            registered.put(def.name(), new RegisteredTool(def, entry.method, entry.handler, entry.proxy, meters));
            defs.add(def);
        }
        this.tools = Collections.unmodifiableMap(registered);
        this.definitions = List.copyOf(defs);
        LOG.log(Level.INFO, () -> "McpTools ready with " + definitions.size() + " tools (maxInFlight=" + maxInFlight + ")");
        for (RegisteredTool tool : registered.values()) {
            LOG.log(Level.DEBUG, () -> "MCP tool '" + tool.definition.name() + "' (group=" + tool.definition.group()
                    + ", readOnly=" + tool.definition.readOnly() + ", destructive=" + tool.definition.destructive()
                    + ", idempotent=" + tool.definition.idempotent() + ")"
                    + (tool.proxy != null ? " -> proxy(" + tool.method + ")"
                            : (tool.method != null ? " -> " + tool.method : " (handler)")));
        }
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Scans {@code types} (classes or interfaces) for {@link cn.managame.ai.annotation.McpTool} methods and builds
     * their {@link McpToolDefinition}s without instantiating any target object. Useful for static schema inspection or
     * building gateway catalogs.
     */
    public static List<McpToolDefinition> scanDefinitions(Collection<? extends Class<?>> types) {
        Objects.requireNonNull(types, "types");
        Map<String, McpToolDefinition> defs = new LinkedHashMap<>();
        for (Class<?> type : types) {
            for (ToolMethod method : ToolMethod.scanType(Objects.requireNonNull(type, "type"))) {
                McpToolDefinition def = method.definition();
                if (defs.putIfAbsent(def.name(), def) != null) {
                    throw new IllegalArgumentException("Duplicate MCP tool name '" + def.name() + "'");
                }
            }
        }
        return List.copyOf(defs.values());
    }

    /** All registered tool definitions, in registration order. */
    public List<McpToolDefinition> definitions() { return definitions; }

    /** The codec used for argument decoding and result encoding. */
    public AiCodec codec() { return codec; }

    /** Current number of in-flight tool invocations. */
    public int inFlight() { return inFlight.get(); }

    public boolean isClosed() { return closed; }

    boolean hasInterceptors() { return !interceptors.isEmpty(); }

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
            metrics.closed();
            LOG.log(Level.WARNING, () -> "MCP tool '" + name + "' refused (closed)");
            deliver(callback, McpToolResult.fail(AI_CLOSED, "McpTools is closed"));
            return;
        }
        Map<String, Object> rawArgs;
        String normalizedJson;
        if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson.trim())) {
            rawArgs = Map.of();
            normalizedJson = "{}";
        } else {
            try {
                rawArgs = Json.parseObject(argumentsJson);
                normalizedJson = argumentsJson;
            } catch (IllegalArgumentException badJson) {
                metrics.invalidArgs();
                LOG.log(Level.WARNING, () -> "Invalid JSON arguments for MCP tool '" + name + "': " + badJson.getMessage());
                deliver(callback, McpToolResult.fail(AI_TOOL_INVALID_ARGS, "Invalid arguments JSON: " + badJson.getMessage()));
                return;
            }
        }
        Map<String, String> safeHeaders = headers == null || headers.isEmpty() ? EMPTY_HEADERS : caseInsensitive(headers);
        invokeParsed(name, rawArgs, normalizedJson, safeHeaders, remoteAddress, callback);
    }

    /** Package-private fast path for {@link McpHttpHandler}, avoiding redundant JSON parsing of {@code arguments}. */
    void invokeParsed(String name, Map<String, Object> rawArgs, String rawJson,
            Map<String, String> headers, SocketAddress remoteAddress, Consumer<McpToolResult> callback) {
        if (closed) {
            metrics.closed();
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
        String effectiveRawJson = rawJson != null ? rawJson : (unmodifiableRaw.isEmpty() ? "{}" : Json.write(unmodifiableRaw));
        Object[] decodedArgs;
        boolean hasRouteKey = tool.method != null && tool.method.hasRouteKey();
        long routeKey = 0;
        if (tool.method != null) {
            try {
                decodedArgs = tool.method.decodeArguments(unmodifiableRaw, effectiveRawJson, codec);
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

        Invocation inv = new Invocation(tool, decodedArgs, unmodifiableRaw, effectiveRawJson,
                hasRouteKey, routeKey, headers, remoteAddress, callback);
        active.add(inv);
        if (closed) {
            inv.abortExternal(McpToolResult.fail(AI_CLOSED, "McpTools is closed"));
            return;
        }
        if (timeout != null) {
            inv.armTimeout();
        }
        try {
            executor.execute(inv.call, inv::run);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "McpToolExecutor rejected MCP tool '" + name + "'", cause);
            inv.abortExternal(McpToolResult.fail(AI_TOOL_EXECUTION_ERROR, describeError(cause)));
        }
    }

    /**
     * Marks this registry closed (subsequent calls fail with {@link cn.managame.FrameworkErrorCodes#AI_CLOSED}), aborts
     * any in-flight asynchronous tool calls with {@code AI_CLOSED} so no HTTP exchange hangs, and closes owned metric
     * meters. Safe to call more than once.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Invocation inv : active) {
            inv.abortExternal(McpToolResult.fail(AI_CLOSED, "McpTools is closed"));
        }
        metrics.close();
    }

    private boolean tryAcquireSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) return false;
            if (inFlight.compareAndSet(current, current + 1)) return true;
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

    private String timeoutMessage(McpToolDefinition def, boolean beforeStart) {
        String head = "MCP tool '" + def.name() + "' timed out after " + timeout;
        if (beforeStart) return head + " before it started; it will not run";
        if (def.readOnly() || def.idempotent()) return head;
        return head + "; it may still take effect, so check its result before calling it again";
    }

    private static boolean isRejectedCode(int code) {
        return code == AI_TOOL_REJECTED
                || code == ROUTE_EXECUTOR_OVERLOADED
                || code == RUNTIME_CLOSED
                || code == ROUTE_EXECUTOR_CLOSED;
    }

    private static boolean isFrameworkFailure(int code) {
        return code >= FIRST_FRAMEWORK_CODE && code <= LAST_FRAMEWORK_CODE;
    }

    private final class Invocation {
        private final RegisteredTool tool;
        private final Object[] decodedArgs;
        private final Consumer<McpToolResult> callback;
        private final long startNanos;
        private final McpToolContext context;
        private final McpToolCall call;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicInteger stage;
        private final AtomicReference<Timeout> timerRef;
        private final Object interceptorLock = new Object();
        private volatile Thread runningThread;
        private volatile boolean anyBeforeEntered;
        private int entered;
        private boolean beforeActive;
        private boolean afterDelivered;
        private McpToolResult pendingAfterResult;
        private McpToolResult syncResult;

        Invocation(RegisteredTool tool, Object[] decodedArgs, Map<String, Object> rawArgs, String rawJson,
                boolean hasRouteKey, long routeKey, Map<String, String> headers, SocketAddress remoteAddress,
                Consumer<McpToolResult> callback) {
            this.tool = tool;
            this.decodedArgs = decodedArgs;
            this.callback = callback;
            this.startNanos = metrics.startTimer();
            this.stage = timeout != null ? new AtomicInteger(QUEUED) : null;
            this.timerRef = timeout != null ? new AtomicReference<>() : null;
            this.context = new McpToolContext(tool.definition.name(), headers, remoteAddress,
                    resultAdapter, this::completeFromContext);
            this.call = new McpToolCall(tool.definition,
                    tool.method != null ? tool.method.method() : null,
                    decodedArgs, rawArgs, rawJson, context, hasRouteKey, routeKey);
        }

        void armTimeout() {
            Timeout handle = AiTimer.newTimeout(t -> {
                boolean beforeStart = stage.compareAndSet(QUEUED, TIMED_OUT);
                if (done.get()) return;
                String message = timeoutMessage(tool.definition, beforeStart);
                if (abortExternal(McpToolResult.fail(AI_TIMEOUT, message))) {
                    LOG.log(Level.WARNING, () -> message);
                }
            }, timeout.toNanos());
            timerRef.set(handle);
            if (done.get()) handle.cancel();
        }

        void run() {
            if (stage != null && !stage.compareAndSet(QUEUED, STARTED)) return;
            if (done.get()) return;
            runningThread = Thread.currentThread();
            boolean hasInterceptors = !interceptors.isEmpty();
            if (hasInterceptors) {
                synchronized (interceptorLock) {
                    beforeActive = true;
                }
            }
            boolean allAllowed = true;
            try {
                for (int i = 0; i < interceptors.size(); i++) {
                    if (done.get()) {
                        allAllowed = false;
                        break;
                    }
                    boolean allowed = interceptors.get(i).before(call);
                    if (!allowed) {
                        allAllowed = false;
                        LOG.log(Level.WARNING, () -> "MCP tool '" + tool.definition.name() + "' rejected by interceptor");
                        if (!done.get()) context.fail(AI_TOOL_REJECTED, "Tool call rejected");
                        break;
                    }
                    synchronized (interceptorLock) {
                        entered = i + 1;
                    }
                    anyBeforeEntered = true;
                }
                if (hasInterceptors) {
                    McpToolResult deferredAfter = null;
                    synchronized (interceptorLock) {
                        beforeActive = false;
                        deferredAfter = pendingAfterResult;
                        pendingAfterResult = null;
                    }
                    if (deferredAfter != null) {
                        runAfterInterceptors(deferredAfter);
                        return;
                    }
                }
                if (allAllowed && !done.get()) {
                    if (tool.proxy != null) {
                        tool.proxy.handle(call);
                    } else if (tool.method != null) {
                        Object result = tool.method.invoke(context, decodedArgs);
                        if (!tool.method.isAsync()) {
                            if (tool.method.returnsVoid()) {
                                context.reply("ok");
                            } else if (result == null) {
                                if (!done.get()) {
                                    IllegalStateException bug = new IllegalStateException(
                                            tool.method + " returned null; only a void tool with McpToolContext answers later");
                                    LOG.log(Level.ERROR, "MCP tool '" + tool.definition.name() + "' returned null", bug);
                                    context.fail(AI_TOOL_EXECUTION_ERROR, bug.getMessage());
                                }
                            } else {
                                context.reply(result);
                            }
                        }
                    } else {
                        tool.handler.handle(context, call.rawArguments());
                    }
                }
            } catch (Throwable cause) {
                if (hasInterceptors) {
                    synchronized (interceptorLock) {
                        beforeActive = false;
                    }
                }
                LOG.log(Level.ERROR, "MCP tool '" + tool.definition.name() + "' threw an exception", cause);
                context.fail(AI_TOOL_EXECUTION_ERROR, describeError(cause));
            } finally {
                runningThread = null;
            }

            McpToolResult completed = syncResult;
            if (completed != null) {
                runAfterInterceptors(completed);
                deliver(callback, completed);
            }
        }

        private boolean completeFromContext(McpToolResult result) {
            if (!markFinished(result)) return false;
            if (Thread.currentThread() == runningThread) {
                syncResult = result;
                return true;
            }
            if (!anyBeforeEntered && !result.isSuccess()) {
                deliver(callback, result);
                return true;
            }
            dispatchCompletion(() -> {
                scheduleOrRunAfter(result);
                deliver(callback, result);
            });
            return true;
        }

        boolean abortExternal(McpToolResult result) {
            if (!markFinished(result)) return false;
            context.markAnswered();
            deliver(callback, result);
            if (!interceptors.isEmpty() && (stage == null || stage.get() == STARTED)) {
                dispatchCompletion(() -> scheduleOrRunAfter(result));
            }
            return true;
        }

        private void dispatchCompletion(Runnable completion) {
            try {
                executor.complete(call, completion);
            } catch (Throwable rejected) {
                LOG.log(Level.WARNING, () -> "McpToolExecutor.complete rejected completion for '"
                        + tool.definition.name() + "': " + describeError(rejected));
                completion.run();
            }
        }

        private void scheduleOrRunAfter(McpToolResult result) {
            synchronized (interceptorLock) {
                if (beforeActive) {
                    pendingAfterResult = result;
                    return;
                }
            }
            runAfterInterceptors(result);
        }

        private boolean markFinished(McpToolResult result) {
            if (!done.compareAndSet(false, true)) return false;
            active.remove(this);
            inFlight.decrementAndGet();
            if (timerRef != null) {
                Timeout t = timerRef.getAndSet(null);
                if (t != null) t.cancel();
            }
            tool.meters.recordLatency(startNanos);
            recordOutcomeMetrics(result);
            return true;
        }

        private void recordOutcomeMetrics(McpToolResult result) {
            if (result.isSuccess()) return;
            int code = result.code();
            if (isRejectedCode(code)) {
                tool.meters.rejected().increment();
            } else if (code == AI_TIMEOUT) {
                metrics.timeout();
                tool.meters.failures().increment();
            } else if (code == AI_CLOSED) {
                metrics.closed();
                tool.meters.failures().increment();
            } else if (isFrameworkFailure(code)) {
                tool.meters.failures().increment();
            } else {
                tool.meters.businessFailures().increment();
            }
        }

        private void runAfterInterceptors(McpToolResult result) {
            int count;
            synchronized (interceptorLock) {
                if (afterDelivered || entered == 0) return;
                afterDelivered = true;
                count = entered;
            }
            for (int i = count - 1; i >= 0; i--) {
                try {
                    interceptors.get(i).after(call, result);
                } catch (Throwable cause) {
                    LOG.log(Level.ERROR, "Uncaught exception in McpToolInterceptor.after for '" + tool.definition.name() + "'", cause);
                }
            }
        }
    }

    private record RegisteredTool(McpToolDefinition definition, ToolMethod method,
            McpToolHandler handler, McpToolProxy proxy, McpMetrics.ToolMeters meters) {}

    public static final class Builder {
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final List<McpToolInterceptor> interceptors = new ArrayList<>();
        private AiCodec codec = AiCodec.basic();
        private McpToolExecutor executor = McpToolExecutor.virtualThreads();
        private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;
        private Duration timeout;
        private Function<Object, McpToolResult> resultAdapter;
        private Metrics metrics = Metrics.noop();
        private String metricsName = "";

        private Builder() {}

        /** Registers every {@link cn.managame.ai.annotation.McpTool} method of each target object. */
        public Builder register(Collection<?> targets) {
            Objects.requireNonNull(targets, "targets");
            for (Object target : targets) {
                for (ToolMethod method : ToolMethod.scan(target)) {
                    String name = method.definition().name();
                    if (entries.putIfAbsent(name, new Entry(method, null, null, null)) != null) {
                        throw new IllegalArgumentException("Duplicate MCP tool name '" + name + "'");
                    }
                }
            }
            return this;
        }

        /**
         * Registers {@link cn.managame.ai.annotation.McpTool} methods from {@code types} (classes or interfaces)
         * without instantiating them, compiling their JSON Schemas, argument decoders and {@code routeKey} extractors
         * while delegating execution to {@code proxy} (for example a stateless gateway node forwarding calls over
         * {@code managame-rpc}).
         */
        public Builder registerProxy(Collection<? extends Class<?>> types, McpToolProxy proxy) {
            Objects.requireNonNull(types, "types");
            Objects.requireNonNull(proxy, "proxy");
            for (Class<?> type : types) {
                for (ToolMethod method : ToolMethod.scanType(Objects.requireNonNull(type, "type"))) {
                    String name = method.definition().name();
                    if (entries.putIfAbsent(name, new Entry(method, null, null, proxy)) != null) {
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
            Json.parseObject(definition.inputSchema());
            if (entries.putIfAbsent(definition.name(), new Entry(null, definition, handler, null)) != null) {
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

        /** Maximum concurrent in-flight tool calls before failing with AI_OVERLOADED; 1024 by default. */
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
            return metrics(metrics, "");
        }

        /** Where metrics are reported, tagged {@code server=name} when {@code name} is non-empty. */
        public Builder metrics(Metrics metrics, String name) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.metricsName = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Validates registrations (including codec compatibility and {@link McpToolExecutor#prepare}) and builds. */
        public McpTools build() {
            boolean checkBasic = codec == BasicAiCodec.INSTANCE;
            boolean hasAdapter = resultAdapter != null;
            for (Entry entry : entries.values()) {
                if (checkBasic && entry.method != null) entry.method.verifyBasicCodec(hasAdapter);
                executor.prepare(entry.definition(), entry.method != null ? entry.method.method() : null);
            }
            return new McpTools(this);
        }

        private record Entry(ToolMethod method, McpToolDefinition explicitDef,
                McpToolHandler handler, McpToolProxy proxy) {
            McpToolDefinition definition() { return method != null ? method.definition() : explicitDef; }
        }
    }
}
